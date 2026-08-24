# Transformer IME

Android向けの、完全オンデバイスTransformerを組み込んだ日本語IME実験プロジェクトです。

## 現在の状態: v0.2.0

- Android `InputMethodService` として登録可能
- QWERTYローマ字入力
- ローマ字 → ひらがな変換
- 文節候補を組み合わせるかな漢字変換
- ビーム探索による複数文節候補生成
- 候補バー
- 学習済みTiny Transformerによる即時ランキング / 次語予測
- 約5M parameterのMoE Transformerによる非同期再ランキング
- 5M推論完了時に候補へ推論時間を表示 (`✦5M xxms`)
- AI ON/OFF
- パスワード欄ではAI予測を停止
- ナビゲーションバーSafe Area対応
- `INTERNET` permissionなし

## v0.2 かな漢字変換

v0.1では読み全体が辞書に一致した場合だけ漢字候補を出していました。v0.2では読みを複数区間に分割し、辞書候補と未変換かなをビーム探索で組み合わせます。

例:

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

v0.1から継続して使う学習済みPoCモデルです。

- 1 Transformer block
- hidden size: 24
- attention heads: 3
- FFN: 48
- context: 12 tokens
- vocabulary: 65 tokens
- model binary: 約34KB

役割:

- 入力直後に候補を高速ランキング
- 次語予測
- v0.2 Mediumモデルのランキングに入る前の安定した意味側シグナル

### Medium MoE Transformer

v0.2で追加した実機性能検証用バックボーンです。

- 約5.0M parameters
- 8 Transformer layers
- hidden size: 128
- 4 attention heads
- 8 FFN experts / layer
- Top-1 expert routing
- FFN hidden: 256
- context: 24 character tokens
- hash vocabulary buckets: 1024

通常のDense TransformerではなくMixture-of-Expertsにしており、全Expertの重みはモデルパラメータとして保持しつつ、各tokenではTop-1 Expertのみを実行します。

入力中はTiny Transformerで即座に候補を表示し、入力が約140ms止まった後にMediumモデルをバックグラウンドスレッドで実行します。完了した場合は候補先頭に次のような表示が出ます。

```text
今日は天気が良い  ✦5M 84ms
```

これにより、実機上でIMEを使いながら5Mモデルの推論遅延を確認できます。

> 注意: v0.2のMedium MoEはアーキテクチャ・メモリ・速度を実機検証するための実験バックボーンです。重みは決定的に生成されるbenchmark weightsで、日本語大規模コーパスによる全面学習済みモデルではありません。候補品質を壊さないよう、学習済みTinyモデルと辞書順位を強いpriorとしてハイブリッド利用しています。

## UI / Safe Area

Androidのgesture navigation領域を `WindowInsets.Type.navigationBars()` から取得し、キーボード最下段へ端末ごとのbottom paddingを追加します。固定dpではなく実際のnavigation bar insetに追従します。

## プライバシー

このIMEはインターネット権限を要求しません。入力文脈と推論は端末内で処理します。

パスワード入力欄ではTransformer候補と学習処理を停止します。

## ビルド

Android Studioでプロジェクトを開くか、Gradle 9.5+ / JDK 17 / Android SDK 36環境で:

```bash
gradle :app:assembleDebug
```

GitHub Actionsでもdebug APKを生成します。

生成先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 次の実装候補

- BSD等の再配布しやすい大規模日本語辞書の導入
- Medium Transformerを実日本語コーパスで学習
- INT8/INT4量子化
- ONNX Runtime / ExecuTorch / NNAPI等との速度比較
- ユーザー辞書・頻度学習
- フリック入力
- 文節境界の手動変更
- AIスコアの詳細デバッグ表示

## License

Prototype. License TBD.
