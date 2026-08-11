# PDFページ集約ツール (Web版)

複数ページのPDFを1枚に集約(N-up割り付け)する、ブラウザだけで動作するWebアプリです。
Androidの標準ブラウザ(Chrome など)で開いてそのまま使えます。サーバー処理は一切なく、
選択したPDFはすべて端末内(ブラウザ)で処理され、外部には送信されません。

`PdfPageConsolidator/` にある同名のAndroidネイティブアプリと同じレイアウトロジック
(余白・間隔・アスペクト比を保った収まり・セル境界線)で実装しています。

## 使い方

1. `index.html` をAndroidのブラウザで開く(下記「使い方(配布方法)」参照)
2. 「PDFファイルを選択」で対象のPDFを選ぶ
3. 割り付けレイアウト(2/4/6/9ページ→1枚)、出力ページサイズ(A4/Letter)、
   向き(縦/横)を選択
4. 「変換する」をタップ
5. 完了後に表示される「変換したPDFをダウンロード」からPDFを保存

## 配布方法・起動方法

以下のいずれかの方法でAndroid上で実行できます。

- **ローカルファイルとして開く**: このフォルダ一式をAndroid端末(またはPC経由で
  端末のストレージ)にコピーし、`index.html` をChromeなどのブラウザで開く
  (`file://` で直接開いても動作します。動作確認済み)
- **簡易サーバーで配信**: 開発機で `python3 -m http.server` などの静的サーバーを
  起動し、同一Wi-Fi内のAndroid端末からブラウザでアクセスする
- **Webサーバー/GitHub Pagesなどで公開**: このフォルダを静的サイトとしてホスティングし、
  そのURLをAndroidのブラウザで開く。HTTPS配信であれば「ホーム画面に追加」で
  PWAとしてインストールし、アプリのようなアイコンから起動することも可能
  (`manifest.webmanifest` / `sw.js` で対応済み)

## 技術構成

- 外部サーバー・バックエンドなし。すべてクライアントサイドJavaScriptで完結
- PDFの読み込み・生成には [pdf-lib](https://pdf-lib.js.org/) を使用
  (`vendor/pdf-lib.min.js` にバンドル済み。CDN不要でオフラインでも動作)
- 各ページはラスタライズせず、元PDFのページをベクターのまま埋め込んで配置するため
  (`embedPages` + `drawPage`)、文字や図形がぼやけず高品質な出力になります
- `pdfConsolidateCore.js`: レイアウト計算・PDF生成のコアロジック
  (ブラウザの `<script>` からも Node.js の `require` からも読み込めるUMD形式)
- `app.js`: ファイル選択・UI操作・進捗表示などの画面制御
- `manifest.webmanifest` / `sw.js` / `icons/`: ホーム画面への追加(PWA)・オフライン
  キャッシュ対応

## 動作確認

Node.js上でのユニットテスト(レイアウト計算・ページ数・エラーハンドリング)に加え、
Playwright + Chromium(Androidを模したモバイルUser-Agent/ビューポート)で
実ブラウザ上での動作を確認済みです。

- ファイル選択 → ページ数表示
- 各種レイアウト(2/4/6/9面付け)× ページサイズ(A4/Letter)× 向き(縦/横)での変換
- 出力PDFのページ数・ページサイズが期待通りであること
- コンソールエラーが出ないこと
- `file://` で直接開いた場合も動作すること

## ディレクトリ構成

```
PdfPageConsolidatorWeb/
├── index.html
├── styles.css
├── app.js
├── pdfConsolidateCore.js      # コアロジック(ブラウザ/Node共用)
├── vendor/pdf-lib.min.js      # pdf-lib (バンドル済み、CDN不要)
├── manifest.webmanifest
├── sw.js                      # オフラインキャッシュ用Service Worker
└── icons/
    ├── icon.svg
    ├── icon-32.png / icon-180.png / icon-192.png / icon-512.png
```
