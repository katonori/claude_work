/**
 * Time layer
 *
 * @author    Cory Hughart <cory@coryhughart.com>
 * @copyright 2026 Cory Hughart
 * @license   https://www.gnu.org/licenses/gpl-3.0.html GPL-3.0-or-later
 * @link      https://cr0ybot.com/project/pebble-watchface-carbon
 */

#pragma once
#include "../modules/settings.h"
#include <pebble.h>

// Time block layout constants — all tweakable values live here.
// The line under the time shows "<date>  <city>". TL_SMALL_H is its rect height
// and TL_DATE_LEAD the font's blank top leading, which the rect overlaps with
// the time canvas above it.
#if PBL_DISPLAY_HEIGHT <= 168
#define TL_SMALL_FONT_KEY FONT_KEY_GOTHIC_18
#define TL_SMALL_H 22
#define TL_DATE_LEAD 4
#else
#define TL_SMALL_FONT_KEY FONT_KEY_GOTHIC_24
#define TL_SMALL_H 28
#define TL_DATE_LEAD 6
#endif
// The time is drawn with the biggest system digit font (LECO_60 on emery,
// LECO_42 elsewhere) and then pixel-scaled up, see prv_time_canvas_update.
// TL_TIME_H is the height of the canvas the scaled digits are centered in;
// TL_STAGE_DY is the font's internal top gap, skipped when staging the text.
#define TL_TIME_SCALED 1
#define TL_TIME_PAD 0
#if PBL_DISPLAY_HEIGHT >= 228
#define TL_TIME_FONT_KEY FONT_KEY_LECO_60_NUMBERS_AM_PM
#define TL_TIME_H 58
#define TL_STAGE_DY 14
// Timezone / AM-PM row
#define TL_TZ_FONT_KEY FONT_KEY_GOTHIC_14
#define TL_TZ_H 16
#define TL_CITY_H 16
#else
#define TL_TIME_FONT_KEY FONT_KEY_LECO_42_NUMBERS
#define TL_TIME_H 44
#define TL_STAGE_DY 0
// Timezone / AM-PM row: tiny font so the time gets the room
#define TL_TZ_FONT_KEY FONT_KEY_GOTHIC_09
#define TL_TZ_H 11
#define TL_CITY_H 11
#endif
// Total visible block height used by main.c to size the layer frame.
// Derived automatically so it can never fall out of sync with the values above.
#define TL_TIME_BLOCK_H \
	((TL_CITY_H - TL_TIME_PAD) + TL_TIME_H + TL_SMALL_H - TL_DATE_LEAD)

typedef struct TimeLayer TimeLayer;

TimeLayer *time_layer_create(GRect frame);
void time_layer_destroy(TimeLayer *layer);
Layer *time_layer_get_layer(TimeLayer *layer);
void time_layer_set_city(TimeLayer *layer, const char *city);
// Override the timezone abbreviation shown left of the clock. Pass an empty
// string to revert to the system-derived value from strftime.
void time_layer_set_timezone(TimeLayer *layer, const char *tz);
// settings is used for date_format only; 24h is read from clock_is_24h_style()
void time_layer_update(TimeLayer *layer, struct tm *tick_time,
                       const Settings *settings);
