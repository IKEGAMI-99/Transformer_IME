# Transformer IME

Android向けの、完全オンデバイス小型Transformerを組み込んだ日本語IME実験プロジェクトです。

## 現在の状態: v0.1.0 PoC

- Android `InputMethodService` として登録可能
- QWERTYローマ字入力
- ローマ字 → ひらがな変換
- 小規模かな漢字辞書
- 候補バー
- **実際にAttention/FFNを計算する小型Transformer推論器**
- Transformerによる候補再ランキング
- Transformerによる次語候補
- AI ON/OFF
- パスワード欄ではAI予測を停止
- `INTERNET` permissionなし

## Transformer PoC

現在同梱予定のモデルは配線・速度検証用の極小モデルです。

- 1 Transformer block
- hidden size: 24
- attention heads: 3
- FFN: 48
- context: 12 tokens
- vocabulary: 65 tokens
- model binary: 約34KB

最終目標の5M〜30M parameterモデルではありません。まずIMEのキー入力から候補表示までのホットパスにTransformer推論を安全に組み込み、次の段階でモデルを大型化します。

Android側では外部MLランタイムを使わず、Kotlinで以下を直接実装しています。

1. Token Embedding + Position Embedding
2. LayerNorm
3. Causal Multi-Head Self Attention
4. Residual connection
5. FFN + GELU
6. Final LayerNorm
7. Vocabulary projection

## プライバシー

このPoCはインターネット権限を要求しません。入力文脈と推論は端末内で処理します。

パスワード入力欄ではTransformer候補を停止します。

## ビルド

Android Studioでプロジェクトを開くか、Gradle 9.5+ / JDK 17 / Android SDK 36環境で:

```bash
gradle :app:assembleDebug
```

GitHub Actionsでもdebug APKを生成する構成です。

生成先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 次の実装

- 学習済み `tiny_transformer.bin` のリポジトリ追加
- 辞書の大幅拡張
- 文節変換
- 5M〜30M parameterモデルへの差し替え
- INT8/INT4量子化
- NNAPI/GPU/ONNX Runtime等との速度比較
- ユーザー辞書・頻度学習
- フリック入力
- 変換候補のAIスコア可視化

## License

Prototype. License TBD.
