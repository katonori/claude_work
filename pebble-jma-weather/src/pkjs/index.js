// 気象庁 (JMA) の防災情報 JSON から天気予報を取得してウォッチに送る PebbleKit JS
var areas = require('./areas');
var keys = require('message_keys');
var OFFICES = areas.OFFICES;
var TELOPS = areas.TELOPS;

var BASE = 'https://www.jma.go.jp/bosai/forecast/data/';
var WEEKDAYS = ['日', '月', '火', '水', '木', '金', '土'];
var DEFAULT_SETTINGS = { auto: false, office: '130000', area: '130010' };
var OVERVIEW_MAX_CHARS = 600;

// ---------------------------------------------------------------- 設定

function loadSettings() {
  try {
    var s = JSON.parse(localStorage.getItem('settings'));
    if (s && s.office) {
      return s;
    }
  } catch (e) { /* 初回 or 壊れている */ }
  return JSON.parse(JSON.stringify(DEFAULT_SETTINGS));
}

function saveSettings(s) {
  localStorage.setItem('settings', JSON.stringify(s));
}

function findOffice(code) {
  for (var i = 0; i < OFFICES.length; i++) {
    if (OFFICES[i].c === code) {
      return OFFICES[i];
    }
  }
  return null;
}

function findAreaInfo(office, code) {
  if (!office) {
    return null;
  }
  for (var i = 0; i < office.a.length; i++) {
    if (office.a[i].c === code) {
      return office.a[i];
    }
  }
  return null;
}

// 緯度経度から一番近い一次細分区域 (気温観測点の位置で判定) を探す
function nearestArea(lat, lon) {
  var best = null;
  var bestDist = Infinity;
  var cosLat = Math.cos(lat * Math.PI / 180);
  OFFICES.forEach(function(office) {
    office.a.forEach(function(a) {
      if (a.la === null || a.lo === null) {
        return;
      }
      var dy = a.la - lat;
      var dx = (a.lo - lon) * cosLat;
      var d = dx * dx + dy * dy;
      if (d < bestDist) {
        bestDist = d;
        best = { office: office.c, area: a.c };
      }
    });
  });
  return best;
}

// ---------------------------------------------------------------- 通信

function getJson(url, callback) {
  var xhr = new XMLHttpRequest();
  xhr.open('GET', url, true);
  xhr.timeout = 15000;
  xhr.onload = function() {
    if (xhr.status !== 200) {
      callback(new Error('HTTP ' + xhr.status));
      return;
    }
    try {
      callback(null, JSON.parse(xhr.responseText));
    } catch (e) {
      callback(e);
    }
  };
  xhr.onerror = function() { callback(new Error('network')); };
  xhr.ontimeout = function() { callback(new Error('timeout')); };
  xhr.send();
}

var outbox = [];
var sending = false;

function enqueue(msg) {
  outbox.push(msg);
  if (!sending) {
    sendNext();
  }
}

function sendNext() {
  var msg = outbox.shift();
  if (!msg) {
    sending = false;
    return;
  }
  sending = true;
  Pebble.sendAppMessage(msg, sendNext, function(e) {
    console.log('sendAppMessage failed: ' + JSON.stringify(e));
    sendNext();
  });
}

// ---------------------------------------------------------------- 天気データの整形

// "2026-10-02T17:00:00+09:00" はすでに日本時間なので文字列のまま扱う
function dateKey(iso) {
  return iso.slice(0, 10);
}

function hourOf(iso) {
  return iso.slice(11, 13);
}

function todayKeyJst() {
  var d = new Date(Date.now() + 9 * 3600 * 1000);
  return d.toISOString().slice(0, 10);
}

function dayDiff(a, b) {
  var ta = Date.UTC(+a.slice(0, 4), +a.slice(5, 7) - 1, +a.slice(8, 10));
  var tb = Date.UTC(+b.slice(0, 4), +b.slice(5, 7) - 1, +b.slice(8, 10));
  return Math.round((tb - ta) / 86400000);
}

function formatDate(key) {
  var y = +key.slice(0, 4);
  var m = +key.slice(5, 7);
  var d = +key.slice(8, 10);
  var w = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return m + '/' + d + '(' + WEEKDAYS[w] + ')';
}

function formatReport(iso) {
  return (+iso.slice(5, 7)) + '/' + (+iso.slice(8, 10)) + ' ' + (+iso.slice(11, 13)) + '時発表';
}

// 気象庁の文は全角スペース区切りなので、折り返しやすいよう半角スペースにする
function tidy(text) {
  return (text || '').replace(/[　\s]+/g, ' ').trim();
}

// 天気コード -> アイコン番号
//   千の位: 雷を伴う / 百の位: 主な天気 / 十の位: 1=時々・一時, 2=後 / 一の位: 副の天気
//   天気: 1=晴 2=曇 3=雨 4=雪
var KIND = { '晴': 1, '曇': 2, '霧': 2, '雨': 3, '雪': 4 };

function iconFor(code) {
  var telop = TELOPS[code] || '';
  var main = 0;
  var sub = 0;
  var rel = 0;
  var mainPos = -1;
  for (var i = 0; i < telop.length; i++) {
    var k = KIND[telop.charAt(i)];
    if (!k) {
      continue;
    }
    if (!main) {
      main = k;
      mainPos = i;
    } else if (k !== main) {
      sub = k;
      var between = telop.slice(mainPos + 1, i);
      rel = (between.indexOf('後') >= 0 || between.indexOf('から') >= 0) ? 2 : 1;
      break;
    }
  }
  if (!main) {
    main = Math.max(1, Math.min(4, +String(code).charAt(0) || 2));
  }
  var thunder = telop.indexOf('雷') >= 0 ? 1 : 0;
  return thunder * 1000 + main * 100 + rel * 10 + sub;
}

function byCode(list, code) {
  for (var i = 0; i < list.length; i++) {
    if (list[i].area.code === code) {
      return i;
    }
  }
  return -1;
}

function byCodes(list, codes) {
  for (var i = 0; codes && i < codes.length; i++) {
    var idx = byCode(list, codes[i]);
    if (idx >= 0) {
      return idx;
    }
  }
  return -1;
}

function clampIndex(idx, list) {
  return Math.max(0, Math.min(idx, list.length - 1));
}

function buildMessage(fc, ov, officeCode, areaCode) {
  var office = findOffice(officeCode);
  var short = fc[0].timeSeries;
  var idx = byCode(short[0].areas, areaCode);
  if (idx < 0) {
    idx = 0;
  }
  var area = short[0].areas[idx];
  var info = findAreaInfo(office, area.area.code) || { t: [], w: [] };
  var today = todayKeyJst();

  // 降水確率 (6時間ごと)
  var popSeries = short[1];
  var popIdx = popSeries ? byCode(popSeries.areas, area.area.code) : -1;
  var pops = {};
  if (popIdx >= 0) {
    popSeries.timeDefines.forEach(function(td, i) {
      var k = dateKey(td);
      pops[k] = pops[k] || ['', '', '', ''];
      pops[k][Math.floor(+hourOf(td) / 6)] = popSeries.areas[popIdx].pops[i];
    });
  }

  // 気温 (00時 = 朝の最低, 09時 = 日中の最高)
  var temps = {};
  var tempSeries = short[2];
  if (tempSeries && tempSeries.areas.length) {
    var tIdx = byCodes(tempSeries.areas, info.t);
    var tArea = tempSeries.areas[tIdx >= 0 ? tIdx : clampIndex(idx, tempSeries.areas)];
    tempSeries.timeDefines.forEach(function(td, i) {
      var k = dateKey(td);
      temps[k] = temps[k] || { min: '', max: '' };
      if (hourOf(td) === '00') {
        temps[k].min = tArea.temps[i];
      } else {
        temps[k].max = tArea.temps[i];
      }
    });
    // 当日分は朝の最低気温が過ぎているので、最高気温と同じ値が入ってくる
    if (temps[today] && temps[today].min === temps[today].max) {
      temps[today].min = '';
    }
  }

  // 週間予報
  var week = [];
  var weekly = fc[1] && fc[1].timeSeries;
  if (weekly && weekly[0] && weekly[0].areas.length) {
    var wIdx = byCodes(weekly[0].areas, info.w);
    if (wIdx < 0) {
      wIdx = 0;
    }
    var wArea = weekly[0].areas[wIdx];
    var wtArea = null;
    if (weekly[1] && weekly[1].areas.length) {
      var wtIdx = byCodes(weekly[1].areas, info.t);
      wtArea = weekly[1].areas[wtIdx >= 0 ? wtIdx : clampIndex(wIdx, weekly[1].areas)];
    }
    weekly[0].timeDefines.forEach(function(td, i) {
      var k = dateKey(td);
      var day = {
        key: k,
        code: wArea.weatherCodes[i],
        pop: wArea.pops ? wArea.pops[i] : '',
        min: wtArea ? wtArea.tempsMin[i] : '',
        max: wtArea ? wtArea.tempsMax[i] : ''
      };
      // 週間予報の1日目は気温が空なので、短期予報の値で補う
      if (temps[k]) {
        day.min = day.min || temps[k].min;
        day.max = day.max || temps[k].max;
      }
      week.push(day);
    });
  }
  var weekByDate = {};
  week.forEach(function(d) { weekByDate[d.key] = d; });

  var msg = {
    AREA: area.area.name,
    REPORT: formatReport(fc[0].reportDatetime)
  };

  var dayCount = Math.min(3, short[0].timeDefines.length);
  for (var i = 0; i < dayCount; i++) {
    var k = dateKey(short[0].timeDefines[i]);
    var diff = dayDiff(today, k);
    var rel = diff === 0 ? '今日' : diff === 1 ? '明日' : diff === 2 ? '明後日' : '';
    var t = temps[k] || { min: '', max: '' };
    var w = weekByDate[k];
    var pop;
    if (pops[k]) {
      pop = pops[k].join(',');
    } else {
      pop = w ? w.pop : '';
    }
    msg[keys.DAY_LABEL + i] = (rel ? rel + ' ' : '') + formatDate(k);
    msg[keys.DAY_TEXT + i] = tidy(area.weathers[i]);
    msg[keys.DAY_ICON + i] = iconFor(area.weatherCodes[i]);
    msg[keys.DAY_TMAX + i] = t.max || (w && w.max) || '-';
    msg[keys.DAY_TMIN + i] = t.min || (w && w.min) || '-';
    msg[keys.DAY_POP + i] = pop || '';
    msg[keys.DAY_WIND + i] = area.winds ? tidy(area.winds[i]) : '';
  }
  msg.DAY_COUNT = dayCount;

  var weekCount = Math.min(7, week.length);
  for (var j = 0; j < weekCount; j++) {
    var d = week[j];
    msg[keys.WEEK_DATE + j] = formatDate(d.key);
    msg[keys.WEEK_ICON + j] = iconFor(d.code);
    msg[keys.WEEK_TELOP + j] = TELOPS[d.code] || '';
    msg[keys.WEEK_POP + j] = d.pop || '-';
    msg[keys.WEEK_TEMP + j] = (d.max || '-') + '/' + (d.min || '-');
  }
  msg.WEEK_COUNT = weekCount;

  var overview = '';
  if (ov) {
    overview = [ov.headlineText, ov.text].filter(function(s) { return s; }).join('\n')
      .replace(/　/g, '').replace(/\n{2,}/g, '\n').trim();
    if (overview.length > OVERVIEW_MAX_CHARS) {
      overview = overview.slice(0, OVERVIEW_MAX_CHARS) + '…';
    }
    overview = (ov.targetArea ? '【' + ov.targetArea + '】\n' : '') + overview;
  }
  return { main: msg, overview: overview || '天気概況はありません' };
}

// ---------------------------------------------------------------- 取得の流れ

var busy = false;

function sendError(text) {
  enqueue({ ERROR: text });
}

function sendResult(result) {
  enqueue(result.main);
  enqueue({ OVERVIEW: result.overview });
}

function fetchForecast(officeCode, areaCode) {
  getJson(BASE + 'forecast/' + officeCode + '.json', function(err, fc) {
    if (err) {
      busy = false;
      console.log('forecast error: ' + err.message);
      sendError('取得に失敗しました (' + err.message + ')');
      return;
    }
    getJson(BASE + 'overview_forecast/' + officeCode + '.json', function(err2, ov) {
      busy = false;
      var result;
      try {
        result = buildMessage(fc, err2 ? null : ov, officeCode, areaCode);
      } catch (e) {
        console.log('parse error: ' + e.message);
        sendError('データの解析に失敗しました');
        return;
      }
      try {
        localStorage.setItem('last', JSON.stringify(result));
      } catch (e) { /* 容量オーバーでも表示はできる */ }
      sendResult(result);
    });
  });
}

function refresh() {
  if (busy) {
    return;
  }
  busy = true;
  var s = loadSettings();
  if (!s.auto) {
    fetchForecast(s.office, s.area);
    return;
  }
  navigator.geolocation.getCurrentPosition(function(pos) {
    var hit = nearestArea(pos.coords.latitude, pos.coords.longitude);
    if (hit) {
      s.office = hit.office;
      s.area = hit.area;
      saveSettings(s);
    }
    fetchForecast(s.office, s.area);
  }, function(err) {
    console.log('geolocation error: ' + err.message);
    // 位置が取れなくても前回の地域で表示する
    fetchForecast(s.office, s.area);
  }, { timeout: 15000, maximumAge: 30 * 60 * 1000 });
}

Pebble.addEventListener('ready', function() {
  try {
    var last = JSON.parse(localStorage.getItem('last'));
    if (last && last.main) {
      sendResult(last);
    }
  } catch (e) { /* キャッシュなし */ }
  refresh();
});

Pebble.addEventListener('appmessage', function(e) {
  if (e.payload.REFRESH !== undefined) {
    refresh();
  }
});

// ---------------------------------------------------------------- 設定画面

function configPage(s) {
  var list = OFFICES.map(function(o) {
    return { c: o.c, n: o.n, a: o.a.map(function(a) { return [a.c, a.n]; }) };
  });
  var html =
    '<!DOCTYPE html><html><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>気象庁天気 設定</title><style>' +
    'body{font-family:sans-serif;margin:0;padding:16px;background:#f2f2f2;color:#222}' +
    'h1{font-size:20px;margin:0 0 16px}' +
    '.card{background:#fff;border-radius:8px;padding:12px 16px;margin-bottom:12px}' +
    'label{display:block;font-size:14px;color:#666;margin:8px 0 4px}' +
    'select{width:100%;font-size:16px;padding:8px}' +
    '.row{display:flex;align-items:center;gap:8px;font-size:16px}' +
    'input[type=checkbox]{width:20px;height:20px}' +
    'button{width:100%;font-size:18px;padding:12px;border:0;border-radius:8px;background:#0068b7;color:#fff}' +
    '.note{font-size:12px;color:#888;margin-top:12px}' +
    '</style></head><body><h1>気象庁天気 設定</h1>' +
    '<div class="card"><label class="row"><input type="checkbox" id="auto">現在地 (GPS) から地域を選ぶ</label></div>' +
    '<div class="card" id="manual"><label for="office">府県予報区</label><select id="office"></select>' +
    '<label for="area">地域</label><select id="area"></select></div>' +
    '<button id="save">保存</button>' +
    '<p class="note">データ: 気象庁ホームページ (www.jma.go.jp) の防災情報</p>' +
    '<script>' +
    'var O=' + JSON.stringify(list) + ',S=' + JSON.stringify(s) + ';' +
    'var $=function(i){return document.getElementById(i)};' +
    'function fillAreas(code,sel){var o=O.filter(function(x){return x.c===code})[0];' +
    '$("area").innerHTML="";o.a.forEach(function(a){var e=document.createElement("option");' +
    'e.value=a[0];e.textContent=a[1];if(a[0]===sel)e.selected=true;$("area").appendChild(e)})}' +
    'O.forEach(function(o){var e=document.createElement("option");e.value=o.c;e.textContent=o.n;' +
    'if(o.c===S.office)e.selected=true;$("office").appendChild(e)});' +
    'fillAreas($("office").value,S.area);' +
    '$("office").onchange=function(){fillAreas(this.value)};' +
    '$("auto").checked=!!S.auto;' +
    'function sync(){$("manual").style.opacity=$("auto").checked?0.4:1}sync();$("auto").onchange=sync;' +
    '$("save").onclick=function(){var r={auto:$("auto").checked,office:$("office").value,area:$("area").value};' +
    'location.href="pebblejs://close#"+encodeURIComponent(JSON.stringify(r))};' +
    '</script></body></html>';
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

Pebble.addEventListener('showConfiguration', function() {
  Pebble.openURL(configPage(loadSettings()));
});

Pebble.addEventListener('webviewclosed', function(e) {
  if (!e || !e.response) {
    return;
  }
  try {
    var r = JSON.parse(decodeURIComponent(e.response));
    if (r.office && findOffice(r.office)) {
      saveSettings({ auto: !!r.auto, office: r.office, area: r.area });
      localStorage.removeItem('last');
      busy = false;
      refresh();
    }
  } catch (err) {
    console.log('config parse error: ' + err.message);
  }
});

module.exports = { buildMessage: buildMessage, iconFor: iconFor, nearestArea: nearestArea };
