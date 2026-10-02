#!/usr/bin/env python3
"""resources/fonts/DotGothic16-jma.ttf を作り直すスクリプト。

DotGothic16 (SIL OFL 1.1) を npm の @fontsource/dotgothic16 から取り出して1つにまとめ、
JIS 第1水準漢字・かな・記号 + 気象庁データに出てくる文字だけに絞り込む。

  pip install fonttools brotli
  npm pack @fontsource/dotgothic16 && tar xzf fontsource-dotgothic16-*.tgz
  python3 tools/make_font.py package/files
"""
import glob
import json
import os
import sys
import tempfile
import urllib.request

from fontTools.merge import Merger
from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'resources', 'fonts', 'DotGothic16-jma.ttf')
EXTRA = '今日明日明後日週間天気予報概況発表時更新中取得失敗通信エラー降水確率最高最低気温日月火水木金土地域設定読み込み中…℃％→'


def jis_chars():
    chars = set(chr(c) for c in range(0x20, 0x7F))
    # 1〜8区 (記号・英数・かな・ギリシャ・罫線) と 16〜47区 (第1水準漢字)
    for hi in list(range(0xA1, 0xA9)) + list(range(0xB0, 0xD0)):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode('euc_jp'))
            except UnicodeDecodeError:
                pass
    return chars


def data_chars():
    chars = set(EXTRA)
    with open(os.path.join(HERE, '..', 'src', 'pkjs', 'areas.js'), encoding='utf-8') as f:
        chars.update(f.read())
    area = json.loads(urllib.request.urlopen(
        'https://www.jma.go.jp/bosai/common/const/area.json').read())
    for code in area['offices']:
        for kind in ('forecast', 'overview_forecast'):
            try:
                body = urllib.request.urlopen(
                    'https://www.jma.go.jp/bosai/forecast/data/%s/%s.json' % (kind, code)).read()
                chars.update(json.dumps(json.loads(body), ensure_ascii=False))
            except Exception:
                pass
    return chars


def main(src_dir):
    tmp = tempfile.mkdtemp()
    ttfs = []
    for woff in sorted(glob.glob(os.path.join(src_dir, 'dotgothic16-*-400-normal.woff'))):
        font = TTFont(woff)
        font.flavor = None
        path = os.path.join(tmp, os.path.basename(woff) + '.ttf')
        font.save(path)
        ttfs.append(path)
    merged = Merger().merge(ttfs)
    cmap = merged.getBestCmap()

    text = {c for c in jis_chars() | data_chars()
            if ord(c) >= 0x20 and ord(c) in cmap and c not in '\r\n\t'}
    opts = Options()
    opts.hinting = False
    opts.layout_features = []
    opts.name_IDs = ['*']
    sub = Subsetter(opts)
    sub.populate(text=''.join(sorted(text)))
    sub.subset(merged)
    merged.save(OUT)
    print('glyphs:', len(text), '->', OUT)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'package/files')
