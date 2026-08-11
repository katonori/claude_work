# PDFページ集約ツール (Android)

複数ページのPDFファイルを読み込み、指定したレイアウト(2/4/6/9ページ割り付け)で
1枚のページに集約(N-up)した新しいPDFを生成するAndroidアプリです。

## 主な機能

- `ACTION_OPEN_DOCUMENT` によるPDFファイルの選択(端末のファイルピッカーを使用。
  ストレージへのアクセス許可は不要)
- 割り付けレイアウトを選択
  - 2ページ → 1枚 (縦2分割 / 横2分割)
  - 4ページ → 1枚 (2×2)
  - 6ページ → 1枚 (2×3 / 3×2)
  - 9ページ → 1枚 (3×3)
- 出力ページサイズ (A4 / Letter) と向き (縦 / 横) を選択
- 各ページを元のアスペクト比を保ったまま各セルに収め、セル間に境界線を表示
- 変換後、`ACTION_CREATE_DOCUMENT` で保存先PDFファイル名/場所を選択
- 進捗表示(処理中のページ数)、失敗時のエラーメッセージ表示

## 実装

- 外部PDFライブラリは使用せず、Android標準API (`android.graphics.pdf.PdfRenderer`,
  `android.graphics.pdf.PdfDocument`) のみで実装しています(`minSdk 21`以降で利用可能)。
- `PdfConsolidator.kt`: PDFの読み込み・レイアウト計算・書き出しのコアロジック
  (`kotlinx.coroutines` で `Dispatchers.IO` 上で実行)
- `MainActivity.kt`: ファイル選択・オプション選択・進捗表示などのUI制御

## ビルド方法

Android Studio (Giraffe以降推奨) でこのディレクトリ (`PdfPageConsolidator/`) を開き、
Gradle同期後に実行してください。CLIの場合:

```bash
cd PdfPageConsolidator
./gradlew assembleDebug
```

- JDK 17、Android SDK (compileSdk 34) が必要です。
- 初回ビルド時に Gradle 8.7 本体、Android Gradle Plugin、AndroidXライブラリ等を
  `google()` / `mavenCentral()` からダウンロードします(要インターネット接続)。

> **注記**: このプロジェクトを作成した環境ではAndroid SDKや `dl.google.com` への
> アクセスが制限されていたため、実際のビルド・実機/エミュレータでの動作確認は
> 行えていません。コードはAndroid標準APIの仕様に基づき作成していますが、
> 実機・Android Studioでのビルド確認をお願いします。

## 動作要件

- `minSdk 21` (Android 5.0) 以上
- `targetSdk 34`

## ディレクトリ構成

```
PdfPageConsolidator/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/katonori/pdfconsolidator/
│       │   ├── MainActivity.kt
│       │   └── PdfConsolidator.kt
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/{strings,colors,themes}.xml
│           └── drawable/ic_launcher.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew / gradlew.bat / gradle/wrapper/
```
