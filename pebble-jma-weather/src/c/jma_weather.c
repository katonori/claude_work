// 気象庁の天気予報を表示する Pebble ウォッチアプリ
//
//   上/下      : 今日・明日・明後日を切り替え
//   選択       : 週間予報
//   選択 長押し : 天気概況
//   上 長押し   : 再読み込み
#include <pebble.h>

#define NUM_DAYS 3
#define NUM_WEEK 7
#define REFRESH_INTERVAL_MS (30 * 60 * 1000)

typedef struct {
  char label[32];
  char text[192];
  int32_t icon;
  char tmax[8];
  char tmin[8];
  char pop[24];
  char wind[160];
} DayForecast;

typedef struct {
  char date[20];
  int32_t icon;
  char telop[48];
  char pop[8];
  char temp[16];
} WeekForecast;

static Window *s_main_window;
static Layer *s_canvas;
static Window *s_week_window;
static MenuLayer *s_week_menu;
static Window *s_overview_window;
static ScrollLayer *s_overview_scroll;
static TextLayer *s_overview_text;

static GFont s_font_jp;
static AppTimer *s_refresh_timer;

static char s_area[64];
static char s_report[32];
static char s_error[96];
static char s_overview[2048];
static DayForecast s_days[NUM_DAYS];
static WeekForecast s_week[NUM_WEEK];
static int s_day_count;
static int s_week_count;
static int s_day_index;
static bool s_loading = true;

// ---------------------------------------------------------------- 色

#define COLOR_HEADER PBL_IF_COLOR_ELSE(GColorCobaltBlue, GColorBlack)
#define COLOR_MAX PBL_IF_COLOR_ELSE(GColorRed, GColorBlack)
#define COLOR_MIN PBL_IF_COLOR_ELSE(GColorBlue, GColorBlack)
#define COLOR_SUB PBL_IF_COLOR_ELSE(GColorDarkGray, GColorBlack)
#define COLOR_SUN PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack)
#define COLOR_CLOUD_FILL PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite)
#define COLOR_CLOUD_LINE PBL_IF_COLOR_ELSE(GColorDarkGray, GColorBlack)
#define COLOR_RAIN PBL_IF_COLOR_ELSE(GColorBlue, GColorBlack)
#define COLOR_SNOW PBL_IF_COLOR_ELSE(GColorPictonBlue, GColorBlack)
#define COLOR_BOLT PBL_IF_COLOR_ELSE(GColorChromeYellow, GColorBlack)

// ---------------------------------------------------------------- 文字列

// UTF-8 の文字の途中で切らないようにコピーする
static void copy_utf8(char *dst, size_t size, const char *src) {
  size_t n = strlen(src);
  if (n >= size) {
    n = size - 1;
    while (n > 0 && (src[n] & 0xC0) == 0x80) {
      n--;
    }
  }
  memcpy(dst, src, n);
  dst[n] = '\0';
}

static void copy_tuple(DictionaryIterator *iter, uint32_t key, char *dst, size_t size) {
  Tuple *t = dict_find(iter, key);
  if (t && t->type == TUPLE_CSTRING) {
    copy_utf8(dst, size, t->value->cstring);
  }
}

static void int_tuple(DictionaryIterator *iter, uint32_t key, int32_t *dst) {
  Tuple *t = dict_find(iter, key);
  if (t) {
    *dst = t->value->int32;
  }
}

// ---------------------------------------------------------------- 天気アイコン

static void draw_sun(GContext *ctx, GPoint c, int s) {
  int r = s * 26 / 100;
  graphics_context_set_stroke_color(ctx, COLOR_SUN);
  graphics_context_set_stroke_width(ctx, s >= 40 ? 3 : 1);
  for (int i = 0; i < 8; i++) {
    int32_t angle = TRIG_MAX_ANGLE * i / 8;
    int32_t sn = sin_lookup(angle);
    int32_t cs = cos_lookup(angle);
    GPoint p1 = GPoint(c.x + sn * (r + s / 10) / TRIG_MAX_RATIO, c.y - cs * (r + s / 10) / TRIG_MAX_RATIO);
    GPoint p2 = GPoint(c.x + sn * (r + s / 4) / TRIG_MAX_RATIO, c.y - cs * (r + s / 4) / TRIG_MAX_RATIO);
    graphics_draw_line(ctx, p1, p2);
  }
  graphics_context_set_fill_color(ctx, COLOR_SUN);
  graphics_fill_circle(ctx, c, r);
#ifndef PBL_COLOR
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_circle(ctx, c, r - 2);
#endif
}

// 雲は「少し大きい輪郭色の図形」の上に「塗り色の図形」を重ねて縁取りを作る
static void cloud_shapes(GContext *ctx, GPoint c, int s, int grow) {
  graphics_fill_circle(ctx, GPoint(c.x - s * 20 / 100, c.y + s * 6 / 100), s * 18 / 100 + grow);
  graphics_fill_circle(ctx, GPoint(c.x + s * 2 / 100, c.y - s * 6 / 100), s * 25 / 100 + grow);
  graphics_fill_circle(ctx, GPoint(c.x + s * 24 / 100, c.y + s * 8 / 100), s * 16 / 100 + grow);
  graphics_fill_rect(ctx,
                     GRect(c.x - s * 20 / 100 - grow, c.y + s * 6 / 100 - grow,
                           s * 44 / 100 + grow * 2, s * 18 / 100 + grow * 2),
                     0, GCornerNone);
}

static void draw_cloud(GContext *ctx, GPoint c, int s) {
  int line = s >= 40 ? 2 : 1;
  graphics_context_set_fill_color(ctx, COLOR_CLOUD_LINE);
  cloud_shapes(ctx, c, s, line);
  graphics_context_set_fill_color(ctx, COLOR_CLOUD_FILL);
  cloud_shapes(ctx, c, s, 0);
}

static void draw_rain(GContext *ctx, GPoint c, int s) {
  draw_cloud(ctx, GPoint(c.x, c.y - s * 14 / 100), s);
  graphics_context_set_stroke_color(ctx, COLOR_RAIN);
  graphics_context_set_stroke_width(ctx, s >= 40 ? 3 : 1);
  for (int i = -1; i <= 1; i++) {
    int x = c.x + i * s * 22 / 100;
    int y = c.y + s * 22 / 100;
    graphics_draw_line(ctx, GPoint(x + s * 4 / 100, y), GPoint(x - s * 4 / 100, y + s * 18 / 100));
  }
}

static void draw_snow(GContext *ctx, GPoint c, int s) {
  draw_cloud(ctx, GPoint(c.x, c.y - s * 14 / 100), s);
  int r = s >= 40 ? 4 : 2;
  for (int i = -1; i <= 1; i++) {
    GPoint p = GPoint(c.x + i * s * 22 / 100, c.y + s * 30 / 100 + (i == 0 ? s * 6 / 100 : 0));
    graphics_context_set_fill_color(ctx, COLOR_SNOW);
    graphics_fill_circle(ctx, p, r);
#ifndef PBL_COLOR
    graphics_context_set_fill_color(ctx, GColorWhite);
    graphics_fill_circle(ctx, p, r - 1);
#endif
  }
}

static void draw_bolt(GContext *ctx, GRect r) {
  int w = r.size.w;
  int h = r.size.h;
  GPoint pts[] = {
    GPoint(r.origin.x + w * 55 / 100, r.origin.y),
    GPoint(r.origin.x + w * 15 / 100, r.origin.y + h * 55 / 100),
    GPoint(r.origin.x + w * 45 / 100, r.origin.y + h * 55 / 100),
    GPoint(r.origin.x + w * 30 / 100, r.origin.y + h),
    GPoint(r.origin.x + w * 85 / 100, r.origin.y + h * 40 / 100),
    GPoint(r.origin.x + w * 55 / 100, r.origin.y + h * 40 / 100),
  };
  GPathInfo info = { .num_points = ARRAY_LENGTH(pts), .points = pts };
  GPath *path = gpath_create(&info);
  graphics_context_set_fill_color(ctx, COLOR_BOLT);
  gpath_draw_filled(ctx, path);
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 1);
  gpath_draw_outline(ctx, path);
  gpath_destroy(path);
}

static void draw_kind(GContext *ctx, GRect r, int kind) {
  int s = r.size.w < r.size.h ? r.size.w : r.size.h;
  GPoint c = grect_center_point(&r);
  switch (kind) {
    case 1: draw_sun(ctx, c, s); break;
    case 3: draw_rain(ctx, c, s); break;
    case 4: draw_snow(ctx, c, s); break;
    default: draw_cloud(ctx, c, s); break;
  }
}

// icon = 雷(千の位) / 主天気(百) / 1=時々・一時, 2=後(十) / 副天気(一)
static void draw_weather_icon(GContext *ctx, GRect r, int32_t icon) {
  int thunder = (icon / 1000) % 10;
  int main_kind = (icon / 100) % 10;
  int rel = (icon / 10) % 10;
  int sub_kind = icon % 10;
  int w = r.size.w;
  int h = r.size.h;

  if (sub_kind && rel == 2) {
    // 「AのちB」: 左上に A、右下に B
    draw_kind(ctx, GRect(r.origin.x, r.origin.y, w * 62 / 100, h * 62 / 100), main_kind);
    draw_kind(ctx, GRect(r.origin.x + w * 38 / 100, r.origin.y + h * 38 / 100, w * 62 / 100, h * 62 / 100),
              sub_kind);
  } else if (sub_kind) {
    // 「A時々B」: A を大きく、B を右下に小さく
    draw_kind(ctx, GRect(r.origin.x, r.origin.y, w * 80 / 100, h * 80 / 100), main_kind);
    draw_kind(ctx, GRect(r.origin.x + w * 52 / 100, r.origin.y + h * 52 / 100, w * 48 / 100, h * 48 / 100),
              sub_kind);
  } else {
    draw_kind(ctx, r, main_kind);
  }
  if (thunder) {
    draw_bolt(ctx, GRect(r.origin.x, r.origin.y + h * 50 / 100, w * 34 / 100, h * 50 / 100));
  }
}

// ---------------------------------------------------------------- メイン画面

static void draw_text(GContext *ctx, const char *text, GFont font, GRect r, GTextAlignment align,
                      GColor color) {
  graphics_context_set_text_color(ctx, color);
  graphics_draw_text(ctx, text, font, r, GTextOverflowModeTrailingEllipsis, align, NULL);
}

static int text_height(const char *text, GFont font, int width, int max_height) {
  GSize size = graphics_text_layout_get_content_size(text, font, GRect(0, 0, width, max_height),
                                                     GTextOverflowModeTrailingEllipsis,
                                                     GTextAlignmentLeft);
  return size.h;
}

// 降水確率: "a,b,c,d" なら 6 時間ごとの 4 区分、それ以外は 1 日の値
static void draw_pops(GContext *ctx, GRect r, const char *pop) {
  static const char *const s_slots[] = { "0-6", "6-12", "12-18", "18-24" };
  GFont small = fonts_get_system_font(FONT_KEY_GOTHIC_14);
  GFont value = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);

  if (!strchr(pop, ',')) {
    static char buf[32];
    snprintf(buf, sizeof(buf), "降水確率 %s%s", pop[0] ? pop : "-", pop[0] ? "%" : "");
    draw_text(ctx, buf, s_font_jp, GRect(r.origin.x, r.origin.y + 8, r.size.w, 20), GTextAlignmentCenter,
              GColorBlack);
    return;
  }

  int cell = r.size.w / 4;
  const char *p = pop;
  for (int i = 0; i < 4; i++) {
    char val[8] = "";
    const char *comma = strchr(p, ',');
    size_t len = comma ? (size_t)(comma - p) : strlen(p);
    if (len >= 4) {
      len = 3;
    }
    memcpy(val, p, len);
    val[len] = '\0';
    p = comma ? comma + 1 : p + len;

    char text[12];
    snprintf(text, sizeof(text), "%s%s", len ? val : "--", len ? "%" : "");
    GRect c = GRect(r.origin.x + cell * i, r.origin.y, cell, r.size.h);
    draw_text(ctx, s_slots[i], small, GRect(c.origin.x, c.origin.y, c.size.w, 16), GTextAlignmentCenter,
              COLOR_SUB);
    draw_text(ctx, text, value, GRect(c.origin.x, c.origin.y + 13, c.size.w, 22), GTextAlignmentCenter,
              len ? GColorBlack : COLOR_SUB);
  }
}

static void canvas_update(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  int w = b.size.w;
  int h = b.size.h;
  int pad = PBL_IF_ROUND_ELSE(w / 9, 4);
  int top = PBL_IF_ROUND_ELSE(h / 14, 0);
  bool tall = h >= 200;

  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, b, 0, GCornerNone);

  // ヘッダー (地域名)
  int header_h = top + 22;
  graphics_context_set_fill_color(ctx, COLOR_HEADER);
  graphics_fill_rect(ctx, GRect(0, 0, w, header_h), 0, GCornerNone);
  draw_text(ctx, s_area[0] ? s_area : "気象庁天気", s_font_jp, GRect(pad, top + 1, w - pad * 2, 20),
            PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), GColorWhite);

  if (s_day_count == 0) {
    const char *msg = s_error[0] ? s_error : "読み込み中…";
    draw_text(ctx, msg, s_font_jp, GRect(pad, h / 2 - 20, w - pad * 2, 60), GTextAlignmentCenter,
              GColorBlack);
    return;
  }

  DayForecast *d = &s_days[s_day_index];
  int y = header_h + 2;

  // 日付と、前後の日があることを示す矢印
  draw_text(ctx, d->label, s_font_jp, GRect(pad, y, w - pad * 2, 20), GTextAlignmentCenter, GColorBlack);
#ifndef PBL_ROUND
  // 丸型画面では端が欠けるので矢印は出さない
  if (s_day_index > 0) {
    draw_text(ctx, "▲", s_font_jp, GRect(w - pad - 16, y, 16, 20), GTextAlignmentRight, COLOR_SUB);
  }
  if (s_day_index < s_day_count - 1) {
    draw_text(ctx, "▼", s_font_jp, GRect(pad, y, 16, 20), GTextAlignmentLeft, COLOR_SUB);
  }
#endif
  y += 20;

  // アイコンと気温
  int icon_size = tall ? 64 : 50;
  int inner_w = w - pad * 2;
  int icon_x = PBL_IF_ROUND_ELSE(w / 2 - icon_size - 4, pad);
  draw_weather_icon(ctx, GRect(icon_x, y, icon_size, icon_size), d->icon);

  int temp_x = PBL_IF_ROUND_ELSE(w / 2 + 4, pad + icon_size + 6);
  int temp_w = PBL_IF_ROUND_ELSE(w / 2 - pad, inner_w - icon_size - 6);
  GFont temp_font = fonts_get_system_font(tall ? FONT_KEY_GOTHIC_28_BOLD : FONT_KEY_GOTHIC_24_BOLD);
  int temp_line = tall ? 30 : 24;
  int temp_y = y + (icon_size - temp_line * 2) / 2 - 4;
  char buf[16];
  snprintf(buf, sizeof(buf), "%s%s", strcmp(d->tmax, "-") ? d->tmax : "--", strcmp(d->tmax, "-") ? "°" : "");
  draw_text(ctx, buf, temp_font, GRect(temp_x, temp_y, temp_w, temp_line + 4),
            PBL_IF_ROUND_ELSE(GTextAlignmentLeft, GTextAlignmentCenter), COLOR_MAX);
  snprintf(buf, sizeof(buf), "%s%s", strcmp(d->tmin, "-") ? d->tmin : "--", strcmp(d->tmin, "-") ? "°" : "");
  draw_text(ctx, buf, temp_font, GRect(temp_x, temp_y + temp_line, temp_w, temp_line + 4),
            PBL_IF_ROUND_ELSE(GTextAlignmentLeft, GTextAlignmentCenter), COLOR_MIN);
  y += icon_size + 2;

  // 下から: 発表時刻 (縦長画面のみ)、降水確率
  int bottom = h - PBL_IF_ROUND_ELSE(h / 9, 0);
  if (tall) {
    const char *footer = s_error[0] ? s_error : s_report;
    bottom -= 16;
    draw_text(ctx, footer, s_font_jp, GRect(pad, bottom - 2, inner_w, 18),
              PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentRight),
              s_error[0] ? COLOR_MAX : COLOR_SUB);
  }
  int pop_h = 36;
  bottom -= pop_h;
  graphics_context_set_stroke_color(ctx, COLOR_CLOUD_FILL);
  graphics_context_set_stroke_width(ctx, 1);
  graphics_draw_line(ctx, GPoint(pad, bottom - 1), GPoint(w - pad, bottom - 1));
  draw_pops(ctx, GRect(pad, bottom, inner_w, pop_h), d->pop);

  // 残りの高さに天気の文章と風
  int avail = bottom - y - 2;
  if (avail >= 16) {
    int th = text_height(d->text, s_font_jp, inner_w, avail);
    draw_text(ctx, d->text, s_font_jp, GRect(pad, y, inner_w, avail), GTextAlignmentCenter, GColorBlack);
    int wind_y = y + th + 2;
    int wind_h = bottom - wind_y - 2;
    if (d->wind[0] && wind_h >= 16) {
      draw_text(ctx, d->wind, s_font_jp, GRect(pad, wind_y, inner_w, wind_h), GTextAlignmentCenter,
                COLOR_SUB);
    }
  }
}

// ---------------------------------------------------------------- 週間予報

static uint16_t week_num_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  return s_week_count > 0 ? s_week_count : 1;
}

static int16_t week_row_height(MenuLayer *menu, MenuIndex *index, void *ctx) {
  return PBL_IF_ROUND_ELSE(menu_layer_is_index_selected(menu, index) ? 56 : 40, 44);
}

static void week_draw_row(GContext *ctx, const Layer *cell, MenuIndex *index, void *data) {
  GRect b = layer_get_bounds(cell);
  bool highlighted = menu_cell_layer_is_highlighted(cell);
  // 丸型画面では選択行以外は円の端にかかるので内側に寄せる
  int pad = PBL_IF_ROUND_ELSE(b.size.w / (highlighted ? 8 : 5), 2);

  graphics_context_set_fill_color(ctx, highlighted ? PBL_IF_COLOR_ELSE(GColorCeleste, GColorWhite)
                                                   : GColorWhite);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
#ifndef PBL_COLOR
  if (highlighted) {
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 1);
    graphics_draw_rect(ctx, grect_inset(b, GEdgeInsets(1)));
  }
#endif

  if (s_week_count == 0) {
    draw_text(ctx, s_error[0] ? s_error : "読み込み中…", s_font_jp, GRect(pad, 10, b.size.w - pad * 2, 20),
              GTextAlignmentCenter, GColorBlack);
    return;
  }

  WeekForecast *d = &s_week[index->row];
  int icon = 28;
  int right_w = 26;
  int text_x = pad + icon + 6;
  int text_w = b.size.w - text_x - right_w - pad - 2;
  int y = (b.size.h - 40) / 2;

  draw_weather_icon(ctx, GRect(pad + 2, y + 1, icon, icon), d->icon);
  char pop[12];
  snprintf(pop, sizeof(pop), "%s%s", strcmp(d->pop, "-") ? d->pop : "", strcmp(d->pop, "-") ? "%" : "");
  draw_text(ctx, pop, fonts_get_system_font(FONT_KEY_GOTHIC_14), GRect(pad, y + 26, icon + 6, 16),
            GTextAlignmentCenter, COLOR_RAIN);

  draw_text(ctx, d->date, s_font_jp, GRect(text_x, y + 1, text_w, 20), GTextAlignmentLeft, GColorBlack);
  draw_text(ctx, d->telop, s_font_jp, GRect(text_x, y + 20, text_w, 20), GTextAlignmentLeft, COLOR_SUB);

  // "最高/最低"
  char tmax[8] = "-";
  char tmin[8] = "-";
  const char *slash = strchr(d->temp, '/');
  if (slash) {
    size_t n = slash - d->temp;
    if (n < sizeof(tmax)) {
      memcpy(tmax, d->temp, n);
      tmax[n] = '\0';
    }
    copy_utf8(tmin, sizeof(tmin), slash + 1);
  }
  GFont tf = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  int rx = b.size.w - pad - right_w;
  draw_text(ctx, tmax, tf, GRect(rx, y - 2, right_w, 20), GTextAlignmentRight, COLOR_MAX);
  draw_text(ctx, tmin, tf, GRect(rx, y + 18, right_w, 20), GTextAlignmentRight, COLOR_MIN);
}

static void week_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_week_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_week_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = week_num_rows,
    .get_cell_height = week_row_height,
    .draw_row = week_draw_row,
  });
  menu_layer_set_click_config_onto_window(s_week_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_week_menu));
}

static void week_window_unload(Window *window) {
  menu_layer_destroy(s_week_menu);
  s_week_menu = NULL;
}

static void show_week(void) {
  if (!s_week_window) {
    s_week_window = window_create();
    window_set_window_handlers(s_week_window, (WindowHandlers) {
      .load = week_window_load,
      .unload = week_window_unload,
    });
  }
  window_stack_push(s_week_window, true);
}

// ---------------------------------------------------------------- 天気概況

static void overview_layout(void) {
  if (!s_overview_text) {
    return;
  }
  Layer *root = window_get_root_layer(s_overview_window);
  GRect b = layer_get_bounds(root);
  int pad = PBL_IF_ROUND_ELSE(0, 4);
  text_layer_set_text(s_overview_text, s_overview[0] ? s_overview : "読み込み中…");
  GSize size = graphics_text_layout_get_content_size(text_layer_get_text(s_overview_text), s_font_jp,
                                                     GRect(0, 0, b.size.w - pad * 2, 4000),
                                                     GTextOverflowModeWordWrap, GTextAlignmentLeft);
  layer_set_frame(text_layer_get_layer(s_overview_text), GRect(pad, 2, b.size.w - pad * 2, size.h + 24));
  scroll_layer_set_content_size(s_overview_scroll, GSize(b.size.w, size.h + 32));
#ifdef PBL_ROUND
  text_layer_enable_screen_text_flow_and_paging(s_overview_text, 4);
#endif
}

static void overview_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect b = layer_get_bounds(root);
  s_overview_scroll = scroll_layer_create(b);
  scroll_layer_set_click_config_onto_window(s_overview_scroll, window);
  s_overview_text = text_layer_create(GRect(0, 0, b.size.w, 2000));
  text_layer_set_font(s_overview_text, s_font_jp);
  text_layer_set_overflow_mode(s_overview_text, GTextOverflowModeWordWrap);
  text_layer_set_text_alignment(s_overview_text, PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));
  scroll_layer_add_child(s_overview_scroll, text_layer_get_layer(s_overview_text));
  layer_add_child(root, scroll_layer_get_layer(s_overview_scroll));
#ifdef PBL_ROUND
  scroll_layer_set_paging(s_overview_scroll, true);
#endif
  overview_layout();
}

static void overview_window_unload(Window *window) {
  text_layer_destroy(s_overview_text);
  scroll_layer_destroy(s_overview_scroll);
  s_overview_text = NULL;
  s_overview_scroll = NULL;
}

static void show_overview(void) {
  if (!s_overview_window) {
    s_overview_window = window_create();
    window_set_window_handlers(s_overview_window, (WindowHandlers) {
      .load = overview_window_load,
      .unload = overview_window_unload,
    });
  }
  window_stack_push(s_overview_window, true);
}

// ---------------------------------------------------------------- 通信

static void schedule_refresh(void);

static void request_refresh(void) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) == APP_MSG_OK) {
    dict_write_uint8(iter, MESSAGE_KEY_REFRESH, 1);
    app_message_outbox_send();
  }
  s_loading = true;
  schedule_refresh();
}

static void refresh_timer_cb(void *data) {
  s_refresh_timer = NULL;
  request_refresh();
}

static void schedule_refresh(void) {
  if (s_refresh_timer) {
    app_timer_cancel(s_refresh_timer);
  }
  s_refresh_timer = app_timer_register(REFRESH_INTERVAL_MS, refresh_timer_cb, NULL);
}

static void redraw_all(void) {
  if (s_canvas) {
    layer_mark_dirty(s_canvas);
  }
  if (s_week_menu) {
    menu_layer_reload_data(s_week_menu);
  }
  overview_layout();
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *t;

  if ((t = dict_find(iter, MESSAGE_KEY_ERROR))) {
    copy_utf8(s_error, sizeof(s_error), t->value->cstring);
    s_loading = false;
  }

  if (dict_find(iter, MESSAGE_KEY_AREA)) {
    copy_tuple(iter, MESSAGE_KEY_AREA, s_area, sizeof(s_area));
    copy_tuple(iter, MESSAGE_KEY_REPORT, s_report, sizeof(s_report));
    s_error[0] = '\0';
    s_loading = false;

    int32_t count = 0;
    int_tuple(iter, MESSAGE_KEY_DAY_COUNT, &count);
    s_day_count = count > NUM_DAYS ? NUM_DAYS : (int)count;
    for (int i = 0; i < s_day_count; i++) {
      DayForecast *d = &s_days[i];
      copy_tuple(iter, MESSAGE_KEY_DAY_LABEL + i, d->label, sizeof(d->label));
      copy_tuple(iter, MESSAGE_KEY_DAY_TEXT + i, d->text, sizeof(d->text));
      int_tuple(iter, MESSAGE_KEY_DAY_ICON + i, &d->icon);
      copy_tuple(iter, MESSAGE_KEY_DAY_TMAX + i, d->tmax, sizeof(d->tmax));
      copy_tuple(iter, MESSAGE_KEY_DAY_TMIN + i, d->tmin, sizeof(d->tmin));
      copy_tuple(iter, MESSAGE_KEY_DAY_POP + i, d->pop, sizeof(d->pop));
      copy_tuple(iter, MESSAGE_KEY_DAY_WIND + i, d->wind, sizeof(d->wind));
    }
    if (s_day_index >= s_day_count) {
      s_day_index = 0;
    }

    count = 0;
    int_tuple(iter, MESSAGE_KEY_WEEK_COUNT, &count);
    s_week_count = count > NUM_WEEK ? NUM_WEEK : (int)count;
    for (int i = 0; i < s_week_count; i++) {
      WeekForecast *d = &s_week[i];
      copy_tuple(iter, MESSAGE_KEY_WEEK_DATE + i, d->date, sizeof(d->date));
      int_tuple(iter, MESSAGE_KEY_WEEK_ICON + i, &d->icon);
      copy_tuple(iter, MESSAGE_KEY_WEEK_TELOP + i, d->telop, sizeof(d->telop));
      copy_tuple(iter, MESSAGE_KEY_WEEK_POP + i, d->pop, sizeof(d->pop));
      copy_tuple(iter, MESSAGE_KEY_WEEK_TEMP + i, d->temp, sizeof(d->temp));
    }
  }

  copy_tuple(iter, MESSAGE_KEY_OVERVIEW, s_overview, sizeof(s_overview));
  redraw_all();
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped: %d", (int)reason);
}

// ---------------------------------------------------------------- ボタン

static void up_click(ClickRecognizerRef recognizer, void *context) {
  if (s_day_index > 0) {
    s_day_index--;
    layer_mark_dirty(s_canvas);
  }
}

static void down_click(ClickRecognizerRef recognizer, void *context) {
  if (s_day_index < s_day_count - 1) {
    s_day_index++;
    layer_mark_dirty(s_canvas);
  }
}

static void select_click(ClickRecognizerRef recognizer, void *context) {
  show_week();
}

static void select_long_click(ClickRecognizerRef recognizer, void *context) {
  show_overview();
}

static void up_long_click(ClickRecognizerRef recognizer, void *context) {
  vibes_short_pulse();
  request_refresh();
}

static void click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_UP, up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN, down_click);
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
  window_long_click_subscribe(BUTTON_ID_SELECT, 500, select_long_click, NULL);
  window_long_click_subscribe(BUTTON_ID_UP, 700, up_long_click, NULL);
}

// ---------------------------------------------------------------- 起動

static void main_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_canvas = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_canvas, canvas_update);
  layer_add_child(root, s_canvas);
}

static void main_window_unload(Window *window) {
  layer_destroy(s_canvas);
  s_canvas = NULL;
}

static void init(void) {
  s_font_jp = fonts_load_custom_font(resource_get_handle(RESOURCE_ID_FONT_JP_16));

  s_main_window = window_create();
  window_set_click_config_provider(s_main_window, click_config);
  window_set_window_handlers(s_main_window, (WindowHandlers) {
    .load = main_window_load,
    .unload = main_window_unload,
  });
  window_stack_push(s_main_window, true);

  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_open(app_message_inbox_size_maximum(), 64);
  // 起動直後は JS 側が ready で自動的に取得するので、ここでは定期更新だけ仕掛ける
  schedule_refresh();
}

static void deinit(void) {
  if (s_refresh_timer) {
    app_timer_cancel(s_refresh_timer);
  }
  window_destroy(s_main_window);
  if (s_week_window) {
    window_destroy(s_week_window);
  }
  if (s_overview_window) {
    window_destroy(s_overview_window);
  }
  fonts_unload_custom_font(s_font_jp);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
