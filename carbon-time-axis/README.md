# Carbon: 気温グラフの下に時刻の目盛りを追加

対象: https://github.com/cr0ybot/carbon (`src/c/ui/temp_layer.c` のみ変更)

## 変更内容
- 気温グラフ(スパークライン)の下端に時刻軸の帯を確保(Emery: 14px / その他: 10px)
- 3時間ごとに目盛り線、6時間ごと(0・6・12・18時)に時刻ラベルを表示
- 時計の 12/24 時間設定に追従(24h: `0 6 12 18` / 12h: `12A 6A 12P 6P`)
- 両端のラベルはグラフ領域内に収まるよう位置を補正
- カラー機種はライトグレー、モノクロ機種は白で描画
- 日付の曜日を短縮表記に変更(`Thu, 5/28`。`%A` → `%a`、設定ページのラベルも `Mon, ...` に)

## 適用方法
```sh
cd carbon
git apply /path/to/0001-temp-graph-time-axis.patch   # または git am
pebble build
```
ビルド済みの `carbon-time-axis.pbw` をそのままインストールすることもできます。

## スクリーンショット
- 時刻表示を大きく(basalt/diorite/flint/aplite: LECO_42 を描画後に 4/3〜3/2 倍へピクセル拡大。システムフォントは42が上限のため)。タイムゾーン/都市名/AM-PM は時刻行から外し、GOTHIC_09 の小フォントで1行にまとめた
`screenshots/` に変更前(before_basalt)と変更後(basalt / basalt 12h / diorite / emery)を同梱。
