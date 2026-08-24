# Transformer IME

Android向けの、完全オンデバイスTransformerを組み込んだ日本語IME実験プロジェクトです。

## 現在の状態: v0.3.0

- Android `InputMethodService` として登録可能
- QWERTYローマ字入力
- ローマ字 → ひらがな変換
- 文節候補を組み合わせるかな漢字変換
- ビーム探索による複数文節候補生成
- 学習済みTiny Transformerによる即時ランキング / 次語予測
- **日本語文コーパスで学習した5,022,784 parameter MoE Transformer**による非同期再ランキング
- 日本語学習済み5M推論完了時に `✦JP5M xxms` を表示
- AI ON/OFF
- パスワード欄ではAI予測を停止
- gesture navigation / system gesture / tappable領域を考慮したSafe Area
- `INTERNET` permissionなし。推論時の通信なし

## かな漢字変換

読みを複数区間に分割し、辞書候補と未変換かなをビーム探索で組み合わせます。

```text
きょうはてんきがいい
  ↓
今日は天気が良い
今日は天気がいい
きょうは天気が良い
...
```

現時点の辞書は実験用の内蔵辞書です。商用IME級の巨大辞書ではありません。

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

v0.3ではv0.2の決定的benchmark weightsを廃止し、実際の日本語文で次文字予測学習した重みをAPKへ同梱します。

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

MoEなので全Expertはモデルパラメータとして保持しますが、各tokenではTop-1 Expertだけを実行します。v0.2より層数を8→4へ減らし、Expertsを8→16へ増やすことで、総パラメータを5M以上に維持しながら実推論量を抑えています。

入力中はTiny Transformerで即座に候補を出し、約140ms入力が止まるとJapanese Medium MoEをバックグラウンドで実行します。

```text
良い感じだと思う  ✦JP5M 32ms
```

`✦JP5M` が表示された場合は、APK同梱の日本語コーパス学習済み重みで再ランキングが完了しています。学習済みassetが存在しないソースビルドではbenchmark fallbackへ切り替わり、表示は `✦5M` になります。

## 日本語コーパス学習

学習スクリプトは `tools/train_medium_moe_japanese.py` です。

学習データ:

- Tatoeba Project 日本語 sentence weekly export
- `jpn_sentences.tsv.bz2`
- text license: Creative Commons Attribution 2.0 France (CC BY 2.0 FR)
- attribution: Tatoeba Project contributors

CIでは日本語文をreservoir samplingし、文字bucket化してcausal next-character predictionで学習します。生のコーパスはAPKへ格納せず、学習後に各tensorをsymmetric INT8へ量子化した `medium_moe_jpn.q8` のみAPKへ含めます。学習条件とSHA-256は `medium_moe_jpn.meta.json` に保存します。

Tatoeba download source:

```text
https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2
```

Tatoeba Project contributorsに感謝します。

## UI / Safe Area

一部のAndroid端末ではIMEウィンドウから `navigationBars()` が十分なbottom insetを返さず、言語切替・キーボードを閉じる領域と最下段キーが重なることがあります。

v0.3では次を比較して最大値を採用します。

- `WindowInsets.Type.navigationBars()`
- `WindowInsets.Type.systemGestures()`
- `WindowInsets.Type.mandatorySystemGestures()`
- `WindowInsets.Type.tappableElement()`
- 最低44dpのIME bottom safety zone

これによりgesture navigation領域の報告値が0または小さい端末でも最下段を上へ逃がします。

## プライバシー

IME本体はインターネット権限を要求しません。入力文脈と推論は端末内で処理します。パスワード入力欄ではTransformer候補を停止します。

日本語コーパスのダウンロードはモデルを作る**ビルド時のみ**です。実機上のIMEがTatoebaへアクセスすることはありません。

## ビルド

通常のソースビルドは日本語モデルassetがなければbenchmark fallbackで動作します。

日本語学習済みAPKを作る場合は先に:

```bash
python tools/train_medium_moe_japanese.py \
  --output app/src/main/assets/medium_moe_jpn.q8 \
  --metadata app/src/main/assets/medium_moe_jpn.meta.json
```

その後:

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

GitHub Actionsは日本語モデルの学習・キャッシュ・loader実推論テスト・APK生成まで自動実行します。

生成先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 次の実装候補

- かな漢字辞書の大幅拡張
- ユーザー辞書・個人頻度学習
- INT4量子化
- ONNX Runtime / ExecuTorch等との速度比較
- フリック入力
- 文節境界の手動変更
- より長い日本語コーパス学習とvalidation perplexity計測

## License

Application prototype: License TBD.

Corpus attribution: Tatoeba Project contributors, text distributed under CC BY 2.0 FR. See Tatoeba's Downloads / Terms of Use for details.
