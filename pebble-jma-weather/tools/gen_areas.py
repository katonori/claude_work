#!/usr/bin/env python3
"""気象庁の定数JSONから src/pkjs/areas.js を生成する。

  python3 tools/gen_areas.py

生成物:
  OFFICES  : 府県予報区 [{c:予報JSONのコード, n:名前, a:[一次細分区域...]}]
             一次細分区域 = {c:コード, n:名前, t:[気温観測点], w:[週間予報区域], la:緯度, lo:経度}
  TELOPS   : 天気コード -> 短い天気名 (例: "101" -> "晴時々曇")
"""
import json
import os
import re
import urllib.request

BASE = 'https://www.jma.go.jp/bosai'
OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'pkjs', 'areas.js')


def get(path):
    with urllib.request.urlopen(BASE + path) as r:
        return r.read().decode('utf-8')


def get_json(path):
    return json.loads(get(path))


def extract_telops():
    # TELOPS は予報ページに埋め込まれた JS オブジェクトなので、そこから抜き出す
    html = get('/forecast/')
    start = html.index('TELOPS={') + len('TELOPS=')
    depth = 0
    for i in range(start, len(html)):
        if html[i] == '{':
            depth += 1
        elif html[i] == '}':
            depth -= 1
            if depth == 0:
                break
    body = html[start:i + 1]
    telops = {}
    for code, arr in re.findall(r'(\d{3}):\[([^\]]*)\]', body):
        fields = json.loads('[' + arr + ']')
        telops[code] = fields[3]
    return telops


def main():
    area = get_json('/common/const/area.json')
    fa = get_json('/forecast/const/forecast_area.json')
    wa5 = get_json('/forecast/const/week_area05.json')
    amedas = get_json('/amedas/const/amedastable.json')

    temp_points = {}
    for entries in fa.values():
        for e in entries:
            temp_points[e['class10']] = e['amedas']

    def latlon(points):
        for p in points:
            if p in amedas:
                s = amedas[p]
                return (round(s['lat'][0] + s['lat'][1] / 60, 3),
                        round(s['lon'][0] + s['lon'][1] / 60, 3))
        return (None, None)

    offices = []
    for code, office in area['offices'].items():
        try:
            fc = get_json('/forecast/data/forecast/%s.json' % code)
        except Exception:
            # 十勝・奄美は親の予報区 (014100 / 460100) のファイルに含まれる
            continue
        areas = []
        for a in fc[0]['timeSeries'][0]['areas']:
            c10 = a['area']['code']
            pts = temp_points.get(c10, [])
            la, lo = latlon(pts)
            areas.append({'c': c10, 'n': a['area']['name'], 't': pts,
                          'w': wa5.get(c10, []), 'la': la, 'lo': lo})
        name = office['name']
        if code == '014100':
            name = '釧路・根室・十勝地方'
        elif code == '460100':
            name = '鹿児島県'
        offices.append({'c': code, 'n': name, 'a': areas})

    telops = extract_telops()
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write('// このファイルは tools/gen_areas.py で生成しています。手で編集しないでください。\n')
        f.write('module.exports.OFFICES = ')
        json.dump(offices, f, ensure_ascii=False, separators=(',', ':'))
        f.write(';\nmodule.exports.TELOPS = ')
        json.dump(telops, f, ensure_ascii=False, separators=(',', ':'))
        f.write(';\n')
    print('offices:', len(offices), 'telops:', len(telops))


if __name__ == '__main__':
    main()
