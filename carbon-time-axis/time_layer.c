/**
 * Time layer
 *
 * @author    Cory Hughart <cory@coryhughart.com>
 * @copyright 2026 Cory Hughart
 * @license   https://www.gnu.org/licenses/gpl-3.0.html GPL-3.0-or-later
 * @link      https://cr0ybot.com/project/pebble-watchface-carbon
 */

#include "time_layer.h"
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct TimeLayer {
	Layer *container;
	TextLayer *city_label;
#if defined(TL_TIME_SCALED)
	Layer *time_canvas; // time digits, drawn pixel-scaled
#else
	TextLayer *time_label;
#endif
	TextLayer *tz_label;   // timezone abbreviation, left of time
	TextLayer *ampm_label; // AM/PM indicator, right of time (12h only)
	TextLayer *date_label;
	char city_buf[24];
	char time_buf[8];
	char tz_buf[8];
	char tz_override[8]; // set by time_layer_set_timezone; overrides strftime
	char ampm_buf[4];
	char date_buf[32];
};

static void prv_remove_leading_zero(char *buf, size_t len) {
	bool prev_nondigit = true;
	size_t i = 0;
	while (buf[i]) {
		if (buf[i] == '0' && prev_nondigit) {
			memmove(&buf[i], &buf[i + 1], len - i - 1);
		} else {
			prev_nondigit = !(buf[i] >= '0' && buf[i] <= '9');
			i++;
		}
	}
}

#if defined(TL_TIME_SCALED)
// Draw the time with the largest system digit font, read the rendered pixels
// back from the frame buffer, and redraw them enlarged by the biggest simple
// ratio that fits the canvas (nearest-neighbour).
// Minimum blank pixels kept on each side of the scaled time
#define TL_TIME_MARGIN 4

static void prv_time_canvas_update(Layer *layer, GContext *ctx) {
	TimeLayer *tl = *(TimeLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int w = bounds.size.w, h = bounds.size.h;
	if (w > 144 || h > TL_TIME_H)
		return;

	graphics_context_set_fill_color(ctx, GColorBlack);
	graphics_fill_rect(ctx, bounds, 0, GCornerNone);
	graphics_context_set_text_color(ctx, GColorWhite);
	graphics_draw_text(ctx, tl->time_buf,
	                   fonts_get_system_font(TL_TIME_FONT_KEY), bounds,
	                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);

	// Snapshot the lit pixels into a 1-bit mask
	static uint8_t mask[TL_TIME_H][144 / 8];
	memset(mask, 0, sizeof(mask));
	int minx = w, maxx = -1, miny = h, maxy = -1;
	GBitmap *fb = graphics_capture_frame_buffer(ctx);
	if (!fb)
		return;
	GBitmapFormat fmt = gbitmap_get_format(fb);
	GPoint origin = layer_convert_point_to_screen(layer, GPointZero);
	for (int y = 0; y < h; y++) {
		GBitmapDataRowInfo row = gbitmap_get_data_row_info(fb, origin.y + y);
		for (int x = 0; x < w; x++) {
			int sx = origin.x + x;
			if (sx < row.min_x || sx > row.max_x)
				continue;
			bool on;
			if (fmt == GBitmapFormat1Bit) {
				on = (row.data[sx / 8] >> (sx % 8)) & 1;
			} else {
				GColor c = (GColor){.argb = row.data[sx]};
				on = c.r || c.g || c.b;
			}
			if (!on)
				continue;
			mask[y][x / 8] |= 1 << (x % 8);
			if (x < minx)
				minx = x;
			if (x > maxx)
				maxx = x;
			if (y < miny)
				miny = y;
			if (y > maxy)
				maxy = y;
		}
	}
	graphics_release_frame_buffer(ctx, fb);
	if (maxx < 0)
		return;

	int bw = maxx - minx + 1, bh = maxy - miny + 1;
	static const uint8_t ratios[][2] = {{3, 2}, {4, 3}, {5, 4}, {6, 5}, {1, 1}};
	int num = 1, den = 1;
	for (unsigned i = 0; i < sizeof(ratios) / sizeof(ratios[0]); i++) {
		if (bw * ratios[i][0] / ratios[i][1] <= w - TL_TIME_MARGIN * 2 &&
		    bh * ratios[i][0] / ratios[i][1] <= h) {
			num = ratios[i][0];
			den = ratios[i][1];
			break;
		}
	}
	int dw = bw * num / den, dh = bh * num / den;
	int ox = (w - dw) / 2, oy = (h - dh) / 2;

	graphics_fill_rect(ctx, bounds, 0, GCornerNone);
	graphics_context_set_fill_color(ctx, GColorWhite);
	for (int dy = 0; dy < dh; dy++) {
		int sy = miny + dy * den / num;
		int run = -1;
		for (int dx = 0; dx <= dw; dx++) {
			bool on = false;
			if (dx < dw) {
				int sx = minx + dx * den / num;
				on = (mask[sy][sx / 8] >> (sx % 8)) & 1;
			}
			if (on && run < 0) {
				run = dx;
			} else if (!on && run >= 0) {
				graphics_fill_rect(ctx, GRect(ox + run, oy + dy, dx - run, 1),
				                   0, GCornerNone);
				run = -1;
			}
		}
	}
}
#endif

TimeLayer *time_layer_create(GRect frame) {
	TimeLayer *tl = malloc(sizeof(TimeLayer));
	if (!tl)
		return NULL;

	tl->city_buf[0] = '\0';
	tl->time_buf[0] = '\0';
	tl->tz_buf[0] = '\0';
	tl->tz_override[0] = '\0';
	tl->ampm_buf[0] = '\0';
	tl->date_buf[0] = '\0';

	tl->container = layer_create(frame);
	int w = frame.size.w;

	// City name — top, small font, full width centered
	GFont city_font = fonts_get_system_font(TL_CITY_FONT_KEY);
	tl->city_label = text_layer_create(GRect(0, 0, w, TL_CITY_H));
	text_layer_set_background_color(tl->city_label, GColorClear);
	text_layer_set_text_color(tl->city_label, GColorWhite);
	text_layer_set_font(tl->city_label, city_font);
	text_layer_set_text_alignment(tl->city_label, GTextAlignmentCenter);
	text_layer_set_text(tl->city_label, tl->city_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->city_label));

	// Time — large centered. LECO_60 on emery (>=228px); LECO_42 pixel-scaled
	// everywhere else. TL_TIME_PAD is the internal top gap measured from the
	// emery font's line metrics.
	// Shift the time label up by TL_TIME_PAD so visible digits start flush with
	// city text
	int time_y = TL_CITY_H - TL_TIME_PAD;
#if defined(TL_TIME_SCALED)
	tl->time_canvas = layer_create_with_data(GRect(0, time_y, w, TL_TIME_H),
	                                         sizeof(TimeLayer *));
	*(TimeLayer **)layer_get_data(tl->time_canvas) = tl;
	layer_set_update_proc(tl->time_canvas, prv_time_canvas_update);
	layer_add_child(tl->container, tl->time_canvas);
#else
	GFont time_font = fonts_get_system_font(TL_TIME_FONT_KEY);
	tl->time_label = text_layer_create(GRect(0, time_y, w, TL_TIME_H));
	text_layer_set_background_color(tl->time_label, GColorClear);
	text_layer_set_text_color(tl->time_label, GColorWhite);
	text_layer_set_font(tl->time_label, time_font);
	text_layer_set_text_alignment(tl->time_label, GTextAlignmentCenter);
	text_layer_set_text(tl->time_label, tl->time_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->time_label));
#endif

	// Timezone — small font, left end of the city row. The time row itself is
	// left free so the time can use the full width.
	GFont small_font = fonts_get_system_font(TL_TZ_FONT_KEY);
	int tz_ampm_y = (TL_CITY_H - TL_TZ_H) / 2;
	tl->tz_label = text_layer_create(GRect(2, tz_ampm_y, 32, TL_TZ_H));
	text_layer_set_background_color(tl->tz_label, GColorClear);
	text_layer_set_text_color(tl->tz_label, GColorLightGray);
	text_layer_set_font(tl->tz_label, small_font);
	text_layer_set_text_alignment(tl->tz_label, GTextAlignmentLeft);
	text_layer_set_text(tl->tz_label, tl->tz_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->tz_label));

	// AM/PM — small font, right end of the city row
	tl->ampm_label = text_layer_create(GRect(w - 34, tz_ampm_y, 32, TL_TZ_H));
	text_layer_set_background_color(tl->ampm_label, GColorClear);
	text_layer_set_text_color(tl->ampm_label, GColorLightGray);
	text_layer_set_font(tl->ampm_label, small_font);
	text_layer_set_text_alignment(tl->ampm_label, GTextAlignmentRight);
	text_layer_set_text(tl->ampm_label, tl->ampm_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->ampm_label));

	// Date — below time
	int date_y = time_y + TL_TIME_H;
	GFont date_font = fonts_get_system_font(TL_SMALL_FONT_KEY);
	tl->date_label = text_layer_create(GRect(0, date_y, w, TL_SMALL_H));
	text_layer_set_background_color(tl->date_label, GColorClear);
	text_layer_set_text_color(tl->date_label, GColorWhite);
	text_layer_set_font(tl->date_label, date_font);
	text_layer_set_text_alignment(tl->date_label, GTextAlignmentCenter);
	text_layer_set_text(tl->date_label, tl->date_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->date_label));

	return tl;
}

void time_layer_destroy(TimeLayer *layer) {
	if (!layer)
		return;
	text_layer_destroy(layer->date_label);
	text_layer_destroy(layer->ampm_label);
	text_layer_destroy(layer->tz_label);
#if defined(TL_TIME_SCALED)
	layer_destroy(layer->time_canvas);
#else
	text_layer_destroy(layer->time_label);
#endif
	text_layer_destroy(layer->city_label);
	layer_destroy(layer->container);
	free(layer);
}

Layer *time_layer_get_layer(TimeLayer *layer) {
	return layer ? layer->container : NULL;
}

void time_layer_set_timezone(TimeLayer *layer, const char *tz) {
	if (!layer || !tz)
		return;
	strncpy(layer->tz_override, tz, sizeof(layer->tz_override) - 1);
	layer->tz_override[sizeof(layer->tz_override) - 1] = '\0';
	// Immediately update the label so it shows even before the next tick
	text_layer_set_text(layer->tz_label, layer->tz_override[0]
	                                         ? layer->tz_override
	                                         : layer->tz_buf);
}

void time_layer_set_city(TimeLayer *layer, const char *city) {
	if (!layer || !city)
		return;
	strncpy(layer->city_buf, city, sizeof(layer->city_buf) - 1);
	layer->city_buf[sizeof(layer->city_buf) - 1] = '\0';
	text_layer_set_text(layer->city_label, layer->city_buf);
}

void time_layer_update(TimeLayer *layer, struct tm *tick_time,
                       const Settings *settings) {
	if (!layer || !tick_time || !settings)
		return;

	bool is_24h = clock_is_24h_style();

	// Time string
	if (is_24h) {
		strftime(layer->time_buf, sizeof(layer->time_buf), "%H:%M", tick_time);
		strncpy(layer->ampm_buf, "24h", sizeof(layer->ampm_buf) - 1);
		layer->ampm_buf[sizeof(layer->ampm_buf) - 1] = '\0';
	} else {
		// 12h: format and strip leading zero
		char tmp[8];
		strftime(tmp, sizeof(tmp), "%I:%M", tick_time);
		const char *src = (tmp[0] == '0') ? tmp + 1 : tmp;
		strncpy(layer->time_buf, src, sizeof(layer->time_buf) - 1);
		layer->time_buf[sizeof(layer->time_buf) - 1] = '\0';
		// AM/PM
		strftime(layer->ampm_buf, sizeof(layer->ampm_buf), "%p", tick_time);
	}
#if defined(TL_TIME_SCALED)
	layer_mark_dirty(layer->time_canvas);
#else
	text_layer_set_text(layer->time_label, layer->time_buf);
#endif
	text_layer_set_text(layer->ampm_label, layer->ampm_buf);
	layer_set_hidden(text_layer_get_layer(layer->ampm_label),
	                 !settings->show_ampm);

	// Timezone abbreviation — use manual override if set (e.g. demo mode),
	// otherwise derive from strftime and hide numeric offsets or empty values.
	if (layer->tz_override[0]) {
		text_layer_set_text(layer->tz_label, layer->tz_override);
	} else {
		strftime(layer->tz_buf, sizeof(layer->tz_buf), "%Z", tick_time);
		bool tz_valid = (layer->tz_buf[0] >= 'A' && layer->tz_buf[0] <= 'Z') &&
		                (layer->tz_buf[1] >= 'A' && layer->tz_buf[1] <= 'Z');
		text_layer_set_text(layer->tz_label, tz_valid ? layer->tz_buf : "");
	}
	layer_set_hidden(text_layer_get_layer(layer->tz_label),
	                 !settings->show_timezone);

	// Date — format string stored in settings; leading zeros stripped
	// automatically.
	strftime(layer->date_buf, sizeof(layer->date_buf), settings->date_format,
	         tick_time);
	prv_remove_leading_zero(layer->date_buf, sizeof(layer->date_buf));
	text_layer_set_text(layer->date_label, layer->date_buf);
}
