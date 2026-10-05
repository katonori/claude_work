/**
 * Temperature layer
 *
 * @author    Cory Hughart <cory@coryhughart.com>
 * @copyright 2026 Cory Hughart
 * @license   https://www.gnu.org/licenses/gpl-3.0.html GPL-3.0-or-later
 * @link      https://cr0ybot.com/project/pebble-watchface-carbon
 */

#include "temp_layer.h"
#include "graph_common.h"
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

// Time axis strip reserved below the sparkline: tick marks every 3 hours plus
// hour labels every 6 hours (aligned to the wall clock: 0, 6, 12, 18).
#if PBL_DISPLAY_HEIGHT >= 228
#define AXIS_FONT_KEY FONT_KEY_GOTHIC_14
#define AXIS_H 14   // strip height, including ticks
#define AXIS_LEAD 5 // GOTHIC_14 internal top leading above digits
#define AXIS_TEXT_H 16
#else
#define AXIS_FONT_KEY FONT_KEY_GOTHIC_09
#define AXIS_H 10
#define AXIS_LEAD 3 // GOTHIC_09 internal top leading above digits
#define AXIS_TEXT_H 11
#endif
#define AXIS_TICK_H 2 // minor (3h) tick length; labeled (6h) ticks are +1

struct TempLayer {
	Layer *layer;
	int16_t current;
	int16_t high;
	int16_t low;
	int8_t hourly[GRAPH_HOURS];
	int8_t apparent_hourly[GRAPH_HOURS];
	uint8_t current_hour;
	uint8_t hours_remaining;
	bool celsius;
};

static void prv_update_proc(Layer *layer, GContext *ctx) {
	TempLayer *tl = *(TempLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int lh = bounds.size.h;
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int range = graph_get_range(); // hours spanned by the sparkline and axis

#if PBL_DISPLAY_HEIGHT >= 228
	GFont font_md = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
#else
	GFont font_md = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
#endif
	graphics_context_set_text_color(ctx, GColorWhite);

	// Left column: high, current, low — three equal zones matching
	// icon_bar_layer. Each item is centered in its zone; sm_lead compensates
	// for GOTHIC_14's internal top leading.
	static char curr_buf[10], high_buf[8], low_buf[8];
	snprintf(high_buf, sizeof(high_buf), "%d", (int)tl->high);
	snprintf(curr_buf, sizeof(curr_buf), "%d", (int)tl->current);
	snprintf(low_buf, sizeof(low_buf), "%d", (int)tl->low);

#if PBL_DISPLAY_HEIGHT >= 228
	int md_h = 28;   // GOTHIC_24_BOLD rect height
	int md_lead = 2; // GOTHIC_24_BOLD internal top leading
	// High/low fonts, largest first: the first that fits the label column is
	// used. Rect height and internal top leading are listed per font.
	static const char *const sm_fonts[] = {FONT_KEY_GOTHIC_24,
	                                       FONT_KEY_GOTHIC_18};
	static const int sm_hs[] = {28, 20};
	static const int sm_leads[] = {2, 2};
#else
	int md_h = 20;   // GOTHIC_18_BOLD rect height
	int md_lead = 2; // GOTHIC_18_BOLD internal top leading
	static const char *const sm_fonts[] = {FONT_KEY_GOTHIC_18,
	                                       FONT_KEY_GOTHIC_14};
	static const int sm_hs[] = {20, 15};
	static const int sm_leads[] = {2, 1};
#endif
	int zone_h =
	    (lh - 2) / 3; // 2px bottom padding keeps low label off the edge
	int label_x = GRAPH_OFFSET_X - 4;

	// High and low share one font so they match: the largest candidate that
	// fits both values in the label column (e.g. 3-digit or negative values
	// fall back to the smaller font).
	int sm_idx = 1;
	for (int i = 0; i < 2; i++) {
		GFont f = fonts_get_system_font(sm_fonts[i]);
		GSize hs = graphics_text_layout_get_content_size(
		    high_buf, f, GRect(0, 0, 144, 40), GTextOverflowModeFill,
		    GTextAlignmentRight);
		GSize ls = graphics_text_layout_get_content_size(
		    low_buf, f, GRect(0, 0, 144, 40), GTextOverflowModeFill,
		    GTextAlignmentRight);
		if (hs.w <= label_x && ls.w <= label_x) {
			sm_idx = i;
			break;
		}
	}
	GFont font_sm = fonts_get_system_font(sm_fonts[sm_idx]);
	int sm_h = sm_hs[sm_idx];
	int sm_lead = sm_leads[sm_idx];

	graphics_draw_text(ctx, high_buf, font_sm,
	                   GRect(0, (zone_h - sm_h) / 2 - sm_lead, label_x, sm_h),
	                   GTextOverflowModeWordWrap, GTextAlignmentRight, NULL);
	graphics_draw_text(
	    ctx, curr_buf, font_md,
	    GRect(0, zone_h + (zone_h - md_h) / 2 - md_lead, label_x, md_h),
	    GTextOverflowModeWordWrap, GTextAlignmentRight, NULL);
	graphics_draw_text(ctx, low_buf, font_sm,
	                   GRect(0, 2 * zone_h + (zone_h - (sm_h - sm_lead)) / 2,
	                         label_x, sm_h - sm_lead),
	                   GTextOverflowModeWordWrap, GTextAlignmentRight, NULL);

	// Vertical separator
	graph_draw_separator(ctx, graph_x, lh);

	// Sparkline — 25 points: current temp followed by 24 hourly forecasts.
	// Point 0 is at the left edge, point 24 at the right edge.
	// The sparkline min/max is based on both actual and apparent temps so the
	// two lines share the same y-scale.
	int16_t pts[25];
	pts[0] = tl->current;
	for (int i = 0; i < range; i++)
		pts[i + 1] = tl->hourly[i];

	int16_t apt[25];
	apt[0] = tl->apparent_hourly[0]; // no separate "current apparent"; use
	                                 // first hourly
	for (int i = 0; i < range; i++)
		apt[i + 1] = tl->apparent_hourly[i];

	// Scale the graph to the day's low/high so the line height matches the
	// high/low labels. The scale is only widened when a plotted value (actual
	// or apparent, e.g. the part of the window falling on the next day) lies
	// outside the day's range, so the lines never leave the graph. Without a
	// valid day range, fall back to the plotted values.
	bool have_day = tl->high > tl->low;
	int16_t t_min = have_day ? tl->low : pts[0];
	int16_t t_max = have_day ? tl->high : pts[0];
	int plotted =
	    (int)tl->hours_remaining < range ? (int)tl->hours_remaining : range;
	for (int i = 0; i <= plotted; i++) {
		if (pts[i] < t_min)
			t_min = pts[i];
		if (pts[i] > t_max)
			t_max = pts[i];
		if (apt[i] < t_min)
			t_min = apt[i];
		if (apt[i] > t_max)
			t_max = apt[i];
	}
	int t_range = t_max - t_min;
	if (t_range < 1)
		t_range = 1;

	// Sparkline area excludes the time axis strip at the bottom
	int plot_h = lh - AXIS_H;
	int pad = 4;
	int graph_h = plot_h - pad * 2;

	// Pre-compute pixel positions for both lines
	int spx[25], spy[25];
	int apx[25], apy[25];
	for (int i = 0; i <= range; i++) {
		spx[i] = graph_x + i * graph_w / range;
		spy[i] = pad + graph_h - ((pts[i] - t_min) * graph_h / t_range);
		apx[i] = spx[i];
		apy[i] = pad + graph_h - ((apt[i] - t_min) * graph_h / t_range);
	}

#if defined(PBL_COLOR)
	int line_bottom = plot_h - 1;
// Two-pass color rendering:
//   Pass 1 — dark fill from the line position down to the bottom of the plot.
//   Pass 2 — light-colored 1px line drawn on top at the actual-temp position.
//   Pass 3 — white 1px line for apparent temperature over everything.
//
// Using paired dark/light shades for each comfort band gives the effect of a
// lighter accent at the line and a darker mass below, making the white
// apparent-temp line legible across all temperature conditions.
//
// Thresholds (°F): <=10 pink  <=32 purple <=45 cyan   <=59 teal
//                  <=76 green <=84 yellow <=96 orange >96 red
#define TEMP_TO_F(t) (tl->celsius ? ((t) * 9 / 5 + 32) : (t))
#define DARK_TEMP_COLOR(tf)                                                    \
	((tf) <= 10   ? GColorPurple                                               \
	 : (tf) <= 32 ? GColorImperialPurple                                       \
	 : (tf) <= 45 ? GColorTiffanyBlue                                          \
	 : (tf) <= 59 ? GColorCadetBlue                                            \
	 : (tf) <= 76 ? GColorKellyGreen                                           \
	 : (tf) <= 84 ? GColorBrass                                                \
	 : (tf) <= 96 ? GColorWindsorTan                                           \
	              : GColorDarkCandyAppleRed)
#define LIGHT_TEMP_COLOR(tf)                                                   \
	((tf) <= 10   ? GColorShockingPink                                         \
	 : (tf) <= 32 ? GColorLavenderIndigo                                       \
	 : (tf) <= 45 ? GColorCyan                                                 \
	 : (tf) <= 59 ? GColorMediumAquamarine                                     \
	 : (tf) <= 76 ? GColorSpringBud                                            \
	 : (tf) <= 84 ? GColorIcterine                                             \
	 : (tf) <= 96 ? GColorChromeYellow                                         \
	              : GColorRed)

	// Pass 1: dark fills
	for (int i = 1; i <= (int)tl->hours_remaining && i <= range; i++) {
		int avg = ((int)pts[i - 1] + (int)pts[i]) / 2;
		graphics_context_set_fill_color(ctx, DARK_TEMP_COLOR(TEMP_TO_F(avg)));
		int x0 = spx[i - 1], y0 = spy[i - 1], x1 = spx[i], y1 = spy[i];
		int dx = x1 - x0, dy = y1 - y0;
		int steps = (abs(dx) > abs(dy)) ? abs(dx) : abs(dy);
		if (steps == 0)
			steps = 1;
		for (int s = 0; s <= steps; s++) {
			int col_x = x0 + dx * s / steps;
			int col_y = y0 + dy * s / steps;
			int col_h = line_bottom - col_y + 1;
			if (col_h > 0) {
				graphics_fill_rect(ctx, GRect(col_x, col_y, 1, col_h), 0,
				                   GCornerNone);
			}
		}
	}

	// Pass 2: light-colored actual-temp line on top of the fill
	graphics_context_set_stroke_width(ctx, 1);
	for (int i = 1; i <= (int)tl->hours_remaining && i <= range; i++) {
		int avg = ((int)pts[i - 1] + (int)pts[i]) / 2;
		graphics_context_set_stroke_color(ctx,
		                                  LIGHT_TEMP_COLOR(TEMP_TO_F(avg)));
		graphics_draw_line(ctx, GPoint(spx[i - 1], spy[i - 1]),
		                   GPoint(spx[i], spy[i]));
	}

	// Pass 3: white apparent-temp line over everything
	graphics_context_set_stroke_color(ctx, GColorWhite);
	for (int i = 1; i <= (int)tl->hours_remaining && i <= range; i++) {
		graphics_draw_line(ctx, GPoint(apx[i - 1], apy[i - 1]),
		                   GPoint(apx[i], apy[i]));
	}

#undef DARK_TEMP_COLOR
#undef LIGHT_TEMP_COLOR
#undef TEMP_TO_F
#else
	// B&W: white actual-temp line + dotted apparent-temp line.
	graphics_context_set_stroke_width(ctx, 1);
	graphics_context_set_stroke_color(ctx, GColorWhite);
	for (int i = 1; i <= (int)tl->hours_remaining && i <= range; i++) {
		graphics_draw_line(ctx, GPoint(spx[i - 1], spy[i - 1]),
		                   GPoint(spx[i], spy[i]));
	}

	// Pebble's b&w path does not offer dashed strokes, so render the apparent
	// temperature as sampled pixels along each segment instead.
	for (int i = 1; i <= (int)tl->hours_remaining && i <= range; i++) {
		graph_draw_dotted_line(ctx, GPoint(apx[i - 1], apy[i - 1]),
		                       GPoint(apx[i], apy[i]), 3);
	}
#endif

	// Noon/midnight ticks — temp is the bottommost graph layer, draw bottom
	// only. Each tick is colored individually: black when it falls within the
	// sparkline fill (contrasts against color), white when it falls in the
	// empty region or there is no sparkline at all.
	{
		int offsets[2] = {
		    (12 - (int)tl->current_hour + 24) % 24, // noon
		    (24 - (int)tl->current_hour) % 24,      // midnight
		};
		graphics_context_set_stroke_width(ctx, 1);
		for (int i = 0; i < 2; i++) {
			int off = offsets[i];
			if (off == 0 || off >= range)
				continue;
			bool in_sparkline = (off < (int)tl->hours_remaining);
			graphics_context_set_stroke_color(
			    ctx, PBL_IF_COLOR_ELSE(in_sparkline ? GColorBlack : GColorWhite,
			                           GColorWhite));
			int tx = graph_x + off * graph_w / range;
			graphics_draw_line(ctx, GPoint(tx, plot_h - 4),
			                   GPoint(tx, plot_h - 1));
		}
	}

	// Time axis: 24h range ticks every 3 hours and labels every 6; 12h range
	// ticks every hour and labels every 3. Offset 0 is the
	// current hour at the left edge of the graph.
	{
		GFont axis_font = fonts_get_system_font(AXIS_FONT_KEY);
		bool is_24h = clock_is_24h_style();
		int right = bounds.size.w;
		graphics_context_set_stroke_width(ctx, 1);
		graphics_context_set_stroke_color(
		    ctx, PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite));
		graphics_context_set_text_color(
		    ctx, PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite));
		int tick_step = range == 12 ? 1 : 3;
		int label_step = range == 12 ? 3 : 6;
		for (int off = 0; off <= range; off++) {
			int hour = ((int)tl->current_hour + off) % 24;
			if (hour % tick_step != 0)
				continue;
			int tx = graph_x + off * graph_w / range;
			bool labeled = (hour % label_step == 0);
			int tick_h = labeled ? AXIS_TICK_H + 1 : AXIS_TICK_H;
			if (tx > graph_x && tx < right)
				graphics_draw_line(ctx, GPoint(tx, plot_h),
				                   GPoint(tx, plot_h + tick_h - 1));
			if (!labeled || off == range)
				continue;

			static char label[4];
			if (is_24h) {
				snprintf(label, sizeof(label), "%d", hour);
			} else {
				int h12 = hour % 12 == 0 ? 12 : hour % 12;
				snprintf(label, sizeof(label), "%d%c", h12,
				         hour < 12 ? 'A' : 'P');
			}
			// Center the label on its tick, clamped inside the graph area
			int label_w = 20;
			int lx = tx - label_w / 2;
			GSize sz = graphics_text_layout_get_content_size(
			    label, axis_font, GRect(0, 0, label_w, AXIS_TEXT_H),
			    GTextOverflowModeFill, GTextAlignmentCenter);
			int text_l = tx - sz.w / 2;
			if (text_l < graph_x + 1)
				lx += graph_x + 1 - text_l;
			if (text_l + sz.w > right)
				lx -= text_l + sz.w - right;
			graphics_draw_text(ctx, label, axis_font,
			                   GRect(lx, plot_h + AXIS_TICK_H + 2 - AXIS_LEAD,
			                         label_w, AXIS_TEXT_H),
			                   GTextOverflowModeFill, GTextAlignmentCenter,
			                   NULL);
		}
	}
}

TempLayer *temp_layer_create(GRect frame) {
	TempLayer *tl = malloc(sizeof(TempLayer));
	if (!tl)
		return NULL;
	tl->current = 0;
	tl->high = 0;
	tl->low = 0;
	tl->current_hour = 0;
	tl->hours_remaining = GRAPH_HOURS;
	tl->celsius = false;
	memset(tl->hourly, 0, sizeof(tl->hourly));
	memset(tl->apparent_hourly, 0, sizeof(tl->apparent_hourly));

	tl->layer = layer_create_with_data(frame, sizeof(TempLayer *));
	*(TempLayer **)layer_get_data(tl->layer) = tl;
	layer_set_update_proc(tl->layer, prv_update_proc);
	return tl;
}

void temp_layer_destroy(TempLayer *layer) {
	if (!layer)
		return;
	layer_destroy(layer->layer);
	free(layer);
}

Layer *temp_layer_get_layer(TempLayer *layer) {
	return layer ? layer->layer : NULL;
}

void temp_layer_set_data(TempLayer *layer, int16_t current, int16_t high,
                         int16_t low, const int8_t hourly[24],
                         const int8_t apparent_hourly[24], uint8_t current_hour,
                         uint8_t hours_remaining) {
	if (!layer)
		return;
	layer->current = current;
	layer->high = high;
	layer->low = low;
	layer->current_hour = current_hour;
	layer->hours_remaining = hours_remaining;
	memcpy(layer->hourly, hourly, GRAPH_HOURS);
	memcpy(layer->apparent_hourly, apparent_hourly, GRAPH_HOURS);
	layer_mark_dirty(layer->layer);
}

void temp_layer_set_unit(TempLayer *layer, bool celsius) {
	if (!layer)
		return;
	layer->celsius = celsius;
	layer_mark_dirty(layer->layer);
}

void temp_layer_set_current_hour(TempLayer *layer, uint8_t current_hour,
                                 uint8_t hours_remaining) {
	if (!layer)
		return;
	layer->current_hour = current_hour;
	layer->hours_remaining = hours_remaining;
	layer_mark_dirty(layer->layer);
}
