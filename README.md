# Transformer IME

Android向けの、完全オンデバイスAI日本語IME実験プロジェクトです。

## 現在の状態: v0.8.0

- Android `InputMethodService`
- 日本語: 12キー・5方向フリック入力
- 英数: QWERTY
- 黒基調の5列レイアウト
- `や`: 上=ゆ / 下=よ / 左右=括弧
- `゛゜大小`: 小文字が存在する文字は小文字を最優先（`つ→っ→づ`, `う→ぅ→ゔ`）
- Mozc OSS由来のSQLite辞書 + 文節ビーム探索
- 読み途中の前方一致変換予測
- Tatoeba由来の文脈→次候補DB
- **Zenzai系かな漢字変換専用モデルを約190.2M parameters搭載**
- llama.cpp / GGUF Q5_K_M / Android arm64 CPU推論
- パスワード欄ではAI予測停止
- `INTERNET` permissionなし。変換・推論は完全オフライン

## v0.8: Mozc draft + Zenzai neural verifier

v0.7までは、辞書が作った候補を汎用的な日本語LMが後段で並べ替えていました。

v0.8ではazooKey/Zenzaiの設計を参考に、かな漢字変換専用モデルへ直接タスクを渡します。

```text
左文脈 + 読み
    ↓
Mozc辞書 / ビーム探索
    ↓
高速なドラフト候補
    ↓
zenz-v3.2-small (~95.1M)
    ↓
ニューラル生成でドラフトを検証・補正
    ↓ 低確信・不一致時のみ
zenz-v3.1-small (~95.1M)
    ↓
最終候補
```

2モデル合計は約190.2M parametersですが、通常はPrimaryだけ推論します。Second opinionはPrimaryが辞書ドラフトと強く食い違い、かつ確信度が低い場合だけ使います。

## Zenzai v3 prompt

変換時はZenzai v3互換の専用タグ形式を使います。

```text
<context-tag><左文脈><input-tag><カタカナ読み><output-tag>
```

これにより、単に「文章として次に自然な文字」を採点するのではなく、モデルが「この読みをこの文脈ではどう変換するか」を直接評価できます。

確定後の予測では、左文脈から次に入力されそうな**読み**をZenzモデルに生成させ、その読みをMozc辞書へ戻して漢字候補を作ります。

## Neural models

### Primary

- `Miwa-Keita/zenz-v3.2-small-gguf`
- GPT-2 architecture
- 約95.1M parameters
- Q5_K_M: 約74MB
- Apache-2.0

### Conditional fallback

- `Miwa-Keita/zenz-v3.1-small-gguf`
- GPT-2 architecture
- 約95.1M parameters
- Q5_K_M: 約74MB
- CC BY-SA 4.0

合計 neural capacity: **約190.2M parameters**

モデルはビルド時に取得しAPKへ同梱します。実行時にモデルをインターネットから取得しません。

## llama.cpp Android runtime

`llama.cpp` revision `b4846` をNDKでarm64向けにビルドし、JNI経由でGGUFモデルを読みます。

- mmap model loading
- CPU inference
- 2〜6 threadsを端末CPU数から選択
- 256 token context
- greedy direct conversion
- top-1 / top-2 logit marginを確信度としてカスケード判定に使用

モデルファイルはAPK assetsから初回だけアプリ内部ストレージへコピーし、その後は同じファイルを再利用します。

## 通常かな漢字変換

Mozc OSS dictionaryをビルド時にAndroid向けSQLiteへ再構成します。

```text
うめだ → 梅田
しんじゅくえき → 新宿駅
はっとり → 服部
```

読み途中のprefix indexも持つため、入力完了前から固有名詞候補を出せます。

## 確定後の文脈予測

即時表示は既存のTiny + corpus context DBを使い、Zenzaiモデルの準備ができるとニューラル予測に更新します。

```text
確定済み文脈
  ↓
Tiny / context DB（即時候補）
  ↓
Zenzai: 次の読みを生成
  ↓
Mozc: 読み→表記候補
  ↓
候補バー更新
```

## セキュリティ

- INTERNET permissionなし
- 入力内容の外部送信なし
- パスワード欄ではAI処理停止
- 生入力ログをRelease版へ保存しない

## Build

GitHub Actionsでは以下を自動検証します。

1. Mozc辞書生成 / 固有名詞テスト
2. 文脈予測DB生成
3. Zenzai v3.2 / v3.1 Q5_K_M取得とGGUF検証
4. pinned llama.cppのhost build
5. Zenzaiによる実かな漢字変換smoke test
6. Kotlin unit tests
7. Android NDK arm64 native build
8. APK内にdual GGUF + `libzenzjni.so` が存在することを検証

第三者モデル・コンポーネントのライセンスは [THIRD_PARTY_MODELS.md](THIRD_PARTY_MODELS.md) を参照してください。
