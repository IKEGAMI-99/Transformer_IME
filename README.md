# Transformer IME

Android向けの、完全オンデバイスTransformerを組み込んだ日本語IME実験プロジェクトです。

## 現在の状態: v0.4.0

- Android `InputMethodService` として登録可能
- **日本語は12キー・5方向フリック入力**
- **英数モードだけQWERTY**（Shift対応）
- 濁点 / 半濁点 / 小文字切替
- **Mozc OSS辞書ベースの通常かな漢字変換**
- 地名・人名・一般語を含む拡張辞書を小型SQLiteとしてオンデバイス検索
- 文節候補を組み合わせるビーム探索
- 学習済みTiny Transformerによる即時ランキング / 次語予測
- **日本語文コーパスで学習した5,022,784 parameter MoE Transformer**による非同期再ランキング
- 日本語学習済み5M推論完了時に `✦JP5M xxms` を表示
- 拡張辞書ロード済みの場合はモデル表示に `·D` を追加
- AI ON/OFF
- パスワード欄ではAI予測を停止
- gesture navigation / system gesture / tappable領域を考慮したSafe Area
- `INTERNET` permissionなし。実機での変換・推論は完全オフライン

## 入力UI

### 日本語

日本語モードではQWERTYローマ字入力を廃止し、スマートフォン向けのフリック入力に変更しました。

基本方向:

```text
       ↑ う
左 い   あ   え 右
       ↓ お
```

`か` なら `か/き/く/け/こ`、`さ` なら `さ/し/す/せ/そ` のように各行へ展開します。

12キー配置:

```text
あ      か      さ      ⌫
た      な      は      ゛゜小
ま      や      ら      ⏎
英数    わ      、。    変換/空白
```

`゛゜小` は直前のかなを濁点・半濁点・小文字へ循環変換します。

### 英数

`英数` を押すとQWERTYへ切り替わります。日本語変換は行わず、英字をそのまま入力します。`かな` でフリックへ戻ります。

## v0.4 通常かな漢字変換

v0.3までの通常変換は、アプリ内に手書きした小さな辞書が中心だったため、特に人名・駅名・地名などの固有名詞に弱い状態でした。

v0.4では **Mozc OSS dictionary** をビルド時に取得し、Android向けの読み→候補SQLiteへ再構成します。MozcはGoogle日本語入力を起源とするOSSの日本語IMEで、OSS辞書にはIPAdic系語彙や公開辞書由来の固有表現、Mozcの手動追加語が含まれます。

例:

```text
うめだ
  ↓
梅田
```

CIでは `dictionary00.txt`〜`dictionary09.txt` と `dictionary_manual/places.tsv` / `words.tsv` を読み、同じ読みの候補をコスト順に圧縮して `mozc_compact.db` を作ります。入力時は変換対象に必要な部分文字列を1回のSQLiteクエリへまとめ、結果をLRUキャッシュします。

このため、巨大な辞書全体をKotlinのHashMapへ展開せずに固有名詞を増やせます。

なお、Mozc OSS辞書はGoogle日本語入力の商用辞書そのものではありません。Google日本語入力専用の大規模Web語彙はOSS版には含まれません。

## AI構成

### Tiny Transformer

入力直後の候補表示と次語予測を担当する高速モデルです。

- 1 Transformer block
- hidden size: 24
- attention heads: 3
- FFN: 48
- context: 12 tokens
- vocabulary: 65 tokens

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

入力中は通常辞書 + Tiny Transformerで即座に候補を出し、約140ms入力が止まるとJapanese Medium MoEをバックグラウンドで実行します。

```text
梅田  ✦JP5M·D 7ms
```

`✦JP5M` は日本語コーパス学習済み5Mモデル、`·D` はMozc拡張辞書がロード済みであることを示します。

## 日本語コーパス学習

学習スクリプトは `tools/train_medium_moe_japanese.py` です。

- Tatoeba Project 日本語 sentence weekly export
- `jpn_sentences.tsv.bz2`
- text license: Creative Commons Attribution 2.0 France (CC BY 2.0 FR)
- attribution: Tatoeba Project contributors

生コーパスはAPKへ格納せず、学習後にsymmetric INT8へ量子化した `medium_moe_jpn.q8` のみを含めます。

## Mozc辞書ビルド

辞書ビルダー:

```bash
python tools/build_mozc_dictionary.py \
  --output app/src/main/assets/mozc_compact.db \
  --metadata app/src/main/assets/mozc_compact.meta.json \
  --max-candidates 8
```

元データは `google/mozc` のOSS辞書です。辞書由来データにはMozc本体とは別の第三者ライセンスが含まれるため、再配布時はMozcの `src/data/dictionary_oss/README.txt` に記載されたIPAdic / ICOT / Okinawa Dictionary等の条件を確認してください。

## UI / Safe Area

v0.3以降、次のbottom insetを比較して最大値を採用し、さらに最低44dpの安全領域を確保します。

- `WindowInsets.Type.navigationBars()`
- `WindowInsets.Type.systemGestures()`
- `WindowInsets.Type.mandatorySystemGestures()`
- `WindowInsets.Type.tappableElement()`

## プライバシー

IME本体はインターネット権限を要求しません。入力文脈・辞書検索・Transformer推論は端末内で処理します。パスワード入力欄ではTransformer候補を停止します。

TatoebaとMozcのダウンロードは**ビルド時のみ**です。インストール後のIMEが外部へ入力内容を送ることはありません。

## CI / ビルド

GitHub Actionsは次を自動実行します。

1. Mozc OSS辞書を取得してcompact SQLiteを生成
2. `うめだ → 梅田` を含むことを検証
3. Tatoeba学習済み5Mモデルを生成またはcache復元
4. Kotlin unit test
5. APK build

ローカルでフル版APKを作る場合は辞書とモデルassetを生成してから:

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
- フリック入力の方向ガイド表示 / 長押し入力
- 絵文字・記号パネル
- INT4量子化
- より長い日本語コーパス学習とvalidation perplexity計測

## License / attribution

Application prototype: License TBD.

Corpus attribution: Tatoeba Project contributors, text distributed under CC BY 2.0 FR.

Dictionary source: Mozc OSS dictionary (`google/mozc`). Mozcの辞書データにはIPAdic等の第三者由来データが含まれます。詳細はMozc upstreamの `src/data/dictionary_oss/README.txt` を参照してください。
