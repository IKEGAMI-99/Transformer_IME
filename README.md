# Transformer IME

Android向けの、完全オンデバイスTransformerを組み込んだ日本語IME実験プロジェクトです。

## 現在の状態: v0.5.0

- Android `InputMethodService` として登録可能
- **日本語は12キー・5方向フリック入力**
- **英数モードだけQWERTY**（Shift対応）
- **Gboard系の5列配置を参考にした黒基調UI**
- 左列: Undo / カーソル左 / 記号 / 英数切替
- 右列: Backspace / カーソル右 / 変換 / Enter
- 濁点 / 半濁点 / 小文字切替
- **Mozc OSS辞書ベースの通常かな漢字変換**
- 地名・人名・一般語を含む拡張辞書をSQLiteとしてオンデバイス検索
- 文節候補を組み合わせるビーム探索
- 学習済みTiny Transformerによる即時ランキング
- **日本語文コーパスで学習した5,022,784 parameter MoE Transformer**による非同期再ランキング
- **変換確定後、直前文脈から次語・次フレーズ候補を予測**
- 次候補はTinyで即表示し、JP5Mが直前96文字から再ランキング
- AI ON/OFF
- パスワード欄ではAI予測を停止
- gesture navigation / system gesture / tappable領域を考慮したSafe Area
- `INTERNET` permissionなし。実機での変換・推論は完全オフライン

## v0.5 日本語入力UI

日本語モードは、一般的なスマートフォン向け12キー配列に合わせて、左右に機能列・中央に3列のかなキーを置きます。Google/Gboardのロゴ・専用画像・専用アセットは使用していません。

```text
↶      あ      か      さ      ⌫
◀      た      な      は      ▶
☺記    ま      や      ら      変換
あa1   ゛゜小   わ      、。    ↵
```

基本フリック方向:

```text
       ↑ う
左 い   あ   え 右
       ↓ お
```

黒背景、白文字、薄いグリッド、ダークグレーのモードキー、ミント系のEnterキーという構成です。英数へ切り替えるとダークテーマのQWERTYになります。

## v0.5 確定後の文脈予測

変換を確定した直後、IMEは `InputConnection` からカーソル直前の文章を取得します。

処理:

```text
確定済み文章
  ↓
直前160文字を取得
  ↓
Tiny Transformer + 文脈候補生成
  ↓  即時表示
候補プール
  ↓
JP5Mが直前96文字で再ランキング
  ↓
次語 / 次フレーズ候補
```

例:

```text
よろしく
  ↓
お願いします
お願いいたします
！
```

```text
確認
  ↓
しました
します
お願いします
```

JP5Mの再ランキングが完了すると候補先頭に次のように表示します。

```text
お願いします  ✦次JP5M 7ms
```

5Mモデルで自由生成するのではなく、Tinyモデル・定型候補・文脈トリガーから作った小さな候補集合をJP5Mに選ばせる構成です。IME用途ではレイテンシと安定性を優先しています。

## 通常かな漢字変換

通常変換は **Mozc OSS dictionary** をビルド時に取得し、Android向けの読み→候補SQLiteへ再構成します。

例:

```text
うめだ → 梅田
はっとり → 服部
たけはら → 竹原
```

CIでは `dictionary00.txt`〜`dictionary09.txt` と `dictionary_manual/places.tsv` / `words.tsv` を読み、同じ読みの候補をコスト順に圧縮して `mozc_compact.db` を作ります。入力時は必要な読みだけSQLiteから取得しLRUキャッシュします。

Mozc OSS辞書はGoogle日本語入力の商用辞書そのものではありません。Google日本語入力専用の大規模Web語彙はOSS版には含まれません。

## AI構成

### Tiny Transformer

- 1 Transformer block
- hidden size: 24
- attention heads: 3
- FFN: 48
- context: 12 tokens
- vocabulary: 65 tokens

役割: 入力直後の変換候補ランキングと、確定後の次候補プール生成。

### Japanese Medium MoE Transformer

- **5,022,784 parameters**
- 4 Transformer layers
- hidden size: 128
- 4 attention heads
- 16 FFN experts / layer
- Top-1 expert routing
- FFN hidden: 272
- context: 24 character tokens
- hash vocabulary buckets: 1024
- symmetric INT8 quantization

変換中:

```text
梅田  ✦JP5M·D 7ms
```

確定後:

```text
お願いします  ✦次JP5M 7ms
```

## 日本語コーパス学習

学習スクリプトは `tools/train_medium_moe_japanese.py` です。

- Tatoeba Project 日本語 sentence weekly export
- `jpn_sentences.tsv.bz2`
- text license: Creative Commons Attribution 2.0 France (CC BY 2.0 FR)
- attribution: Tatoeba Project contributors

生コーパスはAPKへ格納せず、学習後にsymmetric INT8へ量子化した `medium_moe_jpn.q8` のみを含めます。

## UI / Safe Area

次のbottom insetを比較して最大値を採用し、さらに最低44dpの安全領域を確保します。

- `WindowInsets.Type.navigationBars()`
- `WindowInsets.Type.systemGestures()`
- `WindowInsets.Type.mandatorySystemGestures()`
- `WindowInsets.Type.tappableElement()`

## プライバシー

IME本体はインターネット権限を要求しません。入力文脈・辞書検索・Transformer推論は端末内で処理します。パスワード入力欄ではTransformer候補を停止します。

TatoebaとMozcのダウンロードは**ビルド時のみ**です。インストール後のIMEが外部へ入力内容を送ることはありません。

## CI / ビルド

GitHub Actionsは次を自動実行します。

1. Mozc OSS compact辞書を生成 / cache復元
2. `うめだ → 梅田` を検証
3. Tatoeba学習済みJP5Mを生成 / cache復元
4. 文脈次候補・フリック・モデルのunit test
5. APK build

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

生成先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 次の実装候補

- ユーザー辞書・個人頻度学習
- 文節境界の手動変更
- フリック方向のポップアップガイド強化
- 本格的な絵文字・記号パネル
- 次候補プールをMozc品詞情報から生成
- INT4量子化
- より長い日本語コーパス学習

## License / attribution

Application prototype: License TBD.

Corpus attribution: Tatoeba Project contributors, text distributed under CC BY 2.0 FR.

Dictionary source: Mozc OSS dictionary (`google/mozc`). Mozcの辞書データにはIPAdic等の第三者由来データが含まれます。詳細はMozc upstreamの `src/data/dictionary_oss/README.txt` を参照してください。
