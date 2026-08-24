# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書による高速なかな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・文脈予測、端末内SQLiteを使った個人学習 / Personal RAG、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.10.0**

## APKダウンロード

### v0.10.0

[📱 Transformer IME v0.10.0 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32719741487/artifacts/9517376312)

GitHub Actionsでビルド・検証済みのdebug APKです。artifactはZIPとしてダウンロードされ、その中に `app-debug.apk` が入っています。GitHubへのログインが必要な場合があります。

GitHub Actions artifactには保存期限があります。上のリンクが期限切れになった場合は、[最新のAndroid Build](https://github.com/IKEGAMI-99/Transformer_IME/actions/workflows/android.yml) から最新成功ビルドのAPK artifactを取得してください。

---

## 特徴

- Android `InputMethodService`
- 日本語: 12キー・5方向フリック入力
- 英語: QWERTY + 入力補完 + 次単語予測
- Mozc OSS由来SQLite辞書 + 読み途中の前方一致予測
- **Zenzai v3.2-small 約95.1M parameters / Q5_K_M**
- **同一Zenzaiモデルを1入力につき最大10-wayで試行**
- 変換時はZenzai候補を先頭、元の読みを2番目に固定
- 確定後は文脈から次候補を生成
- **Personal RAG**: 過去の変換・次候補・通常確定文を端末内DBから検索して候補へ再投入
- 日本語 / 英語の個人学習
- 学習内容の閲覧・全削除
- 絵文字パネル + 最近使った絵文字
- 数字 / 記号パネル + `123` 長押し数字ファン
- Backspace長押し連続削除
- **Audio Pulse**: 再生中のシステム音量に反応してキーボード背景が発光
- パスワード欄ではAI処理・個人学習を停止
- `INTERNET` permissionなし
- 入力・学習・推論は端末内で完結

---

## v0.10.0

v0.10では、IMEを単なる「Zenzai付きかな漢字変換」から、**使うほど本人向けに候補が育つオンデバイスIME**へ拡張しました。

### 1. Personal RAG

変換結果を単純に順位補正するだけではなく、過去に使った語や文脈続きをローカルSQLiteから検索し、候補生成前のプールへ再投入します。

```text
現在の読み / 左文脈
        ↓
Personal RAG検索
        ↓
過去に使った変換・続きを取得
        ↓
Mozc / context DB / Tiny候補と統合
        ↓
Zenzai 95M ×10でニューラル順位付け
        ↓
候補バー
```

たとえば過去に何度か

```text
今日の撮影ですが集合時間は → 10時です
```

のような文章を入力すると、似た左文脈で過去の続きを候補へ戻せるようになります。

Personal RAGはモデル本体を再学習しません。学習内容は端末内の `user_learning.db` に保存され、次回入力から即座に利用されます。

### 学習する情報

- `conversion`: 読み → 選択した変換
- `next`: 文脈 → 選択した次候補
- `memory`: 普通に確定した文章の文脈続きをRAG記憶として保存
- `english`: 英字prefix → 選択した単語
- `english_next`: 英語文脈 → 次単語

頻度と最終使用時刻を使ってスコアリングし、最近よく使う候補ほど上がりやすくしています。

学習DBは最大約12,000行を目安に自動整理します。

### 学習内容の確認 / 削除

アプリ本体から

- `学習内容を表示`
- `学習内容をすべて削除`

を利用できます。

何を何回使ったか、最後にいつ使ったかまで確認できます。

---

## 日本語入力

### 12キーフリック

5方向フリック入力に対応しています。

- 中央: 基本文字
- 左 / 上 / 右 / 下: 各行の別文字
- `゛゜大小`: 濁点・半濁点・小文字切替
- `変換`: 現在の第1候補を確定
- `記号`: 記号候補を表示

`゛゜大小` は小文字が存在する場合、小文字を優先して巡回します。

例:

```text
つ → っ → づ
う → ぅ → ゔ
```

### Mozc辞書

Mozc OSS dictionaryをビルド時にAndroid向けSQLiteへ再構成しています。

```text
うめだ       → 梅田
しんじゅくえき → 新宿駅
はっとり     → 服部
```

prefix indexを持つため、読みを最後まで入力する前から固有名詞候補を出せます。

---

## Zenzaiニューラル変換

### モデル

- `Miwa-Keita/zenz-v3.2-small-gguf`
- GPT-2 architecture
- 約95.1M parameters
- Q5_K_M
- GGUF 約74MB
- Apache-2.0

v0.8系で使っていた2モデル構成は廃止し、現在は**Zenzai v3.2-small 1モデルのみ**です。

モデル数を増やす代わりに、同一モデルの生成を最大10分岐させます。

```text
左文脈 + 読み
      ↓
Mozc候補
      ↓
Zenzai v3.2-small 95M
      ├─ trial 1
      ├─ trial 2
      ├─ trial 3
      ├─ ...
      └─ trial 10
      ↓
ニューラル候補を比較
      ↓
AI第1候補 + 元の読み + その他候補
```

候補バーではZenzaiによる先頭候補に `✦Z95×10` 系の表示を付けます。

### Zenzai v3 prompt

かな漢字変換ではZenzai v3互換の専用タグ形式を使用します。

```text
<context-tag><左文脈><input-tag><カタカナ読み><output-tag>
```

単なる一般言語モデルとして文章続きを生成するのではなく、**左文脈を踏まえて、この読みをどう変換するか**を直接モデルへ渡します。

---

## 確定後の次候補

確定後は複数系統の予測を統合します。

```text
確定済み文脈
   ↓
Personal RAG
Tiny predictor
context prediction DB
   ↓
候補プール
   ↓
Zenzai 95M ×10 rerank
   ↓
次候補バー更新
```

RAG候補が利用された場合は候補バーのAI表示に `R` が付きます。

---

## 英語QWERTY

v0.10から英語入力もcompositionを持つようになり、候補バーを利用できます。

- prefix入力補完
- スペル / 単語候補
- 次単語予測
- 過去に選択した英単語の個人学習
- 英文脈からのPersonal RAG
- Shift
- Backspace長押し連続削除

```text
tha...
  ↓
thanks / thank / that ...
```

英語候補も外部APIを使用せず、すべて端末内で処理します。

---

## 絵文字・数字・記号

### 絵文字

日本語12キー左側の `😊` から絵文字パネルを開けます。

- 絵文字一覧
- 最近使った絵文字を先頭へ表示
- タップで直接入力

### 数字

右側の `123` から数字 / 記号パネルを開けます。

通常タップ:

```text
数字 + 記号パネル
```

長押し:

```text
      4 5 6
   3       7
 2           8
1             9
        0
```

数字を扇状に展開して素早く選択できます。

---

## Backspace連続削除

Backspaceはタップで1文字削除、長押しで連続削除します。

押し続けると一定時間後にリピートが始まり、その後さらに削除速度が上がります。

日本語composition中・英語composition中・通常テキストのすべてで動作します。

---

## Audio Pulse

Audio Pulseを有効にすると、Androidの `AudioPlaybackCapture` で取得可能なシステム再生音の**音量レベルだけ**を解析し、キーボード背景をリアルタイムに発光させます。

```text
システム再生音
    ↓
AudioPlaybackCapture
    ↓
PCM RMS計算
    ↓
0.0 ～ 1.0 に正規化
    ↓
IME背景のシアン / 紫グロー
```

音声そのものは学習DBやファイルへ保存しません。

### 有効化

1. Transformer IMEアプリ本体を開く
2. `Audio Pulse 背景` をON
3. `RECORD_AUDIO` を許可
4. Androidの画面 / 音声キャプチャ確認を許可

Audio PulseはForeground Serviceとして動作します。

再生アプリ側がAudioPlaybackCaptureを禁止している場合、そのアプリの音には反応しません。

---

## プライバシー / セキュリティ

Transformer IMEは入力データをクラウドへ送信しません。

- `INTERNET` permissionなし
- 入力内容の外部送信なし
- ZenzaiモデルはAPKへ同梱
- Mozc辞書 / context DBもAPKへ同梱
- 個人学習DBは端末内SQLiteのみ
- `android:allowBackup="false"`
- パスワード欄ではAI候補生成を停止
- パスワード欄では個人学習も停止
- Audio Pulseの音声データは保存しない

Audio Pulse用に以下の権限のみ追加しています。

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`

---

## llama.cpp Android runtime

azooKeyのZenzai tokenizer対応 `llama.cpp` revision `b4846` をAndroid NDKでarm64向けにビルドし、JNI経由でGGUFを読み込みます。

- arm64-v8a
- GGUF Q5_K_M
- mmap model loading
- CPU inference
- JNI native runtime `libzenzjni.so`
- 最大10-way生成
- モデルファイルは初回のみAPK assetsからアプリ内部ストレージへコピー

---

## Android要件

- minSdk: **31**
- targetSdk: **36**
- ABI: **arm64-v8a**
- Java: **17**
- NDK: **27.2.12479018**

---

## Build

GitHub Actionsで以下を自動検証します。

1. Mozc辞書生成 / キャッシュ復元
2. Mozc exact / prefix予測テスト
3. 日本語context prediction DB生成 / 検証
4. Zenzai v3.2-small Q5_K_M取得 / GGUF検証
5. azooKey `llama.cpp b4846` host build
6. 実Zenzaiかな漢字変換 smoke test
7. 95M single-model / 10-way構成のsource verification
8. Kotlin unit tests
9. Android SDK / NDK / CMakeセットアップ
10. arm64 debug APK build
11. APK内の単一GGUF + `libzenzjni.so` 検証
12. APK artifact upload

ローカルでの基本ビルド:

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

Zenzai GGUFや生成済み辞書が存在しないクリーン環境では、GitHub Actionsと同様に事前生成 / 取得が必要です。

---

## v0.10アーキテクチャ

```text
                         ┌──────────────────────┐
                         │   Transformer IME    │
                         └──────────┬───────────┘
                                    │
                ┌───────────────────┴───────────────────┐
                │                                       │
          Japanese Flick                         English QWERTY
                │                                       │
                ▼                                       ▼
        Mozc SQLite dictionary                 English predictor
                │                                       │
                ├───────────────┐               ┌───────┤
                │               │               │       │
                ▼               ▼               ▼       ▼
          context DB       Personal RAG     prefix   next word
                │               │               │       │
                └───────┬───────┘               └───┬───┘
                        │                           │
                        ▼                           ▼
              candidate pool / personalization
                        │
                        ▼
               Zenzai 95M ×10 rerank
                        │
                        ▼
                   Candidate Bar

AudioPlaybackCapture ──→ RMS ──→ Audio Pulse background
```

---

## バージョン履歴

### v0.10.0

- Personal RAG
- 日本語 / 英語個人学習
- 学習内容ビューア / 全削除
- 英語QWERTY候補
- Audio Pulse
- 絵文字パネル
- 数字 / 記号パネル
- `123` 長押し数字ファン
- Backspace長押し連続削除
- 日本語左右カーソルキーを絵文字 / `123` へ置換

### v0.9.x

- Zenzai v3.2-small単一モデル化
- 95.1M parameters
- 10-wayニューラル推論
- 元の読みを候補2番目へ固定
- ローカル個人学習の基礎実装

### v0.8.x

- Zenzaiかな漢字変換を導入
- 複数ニューラル候補
- Zenzaiによる次読み予測

---

## Third-party models / licenses

第三者モデル・コンポーネントの詳細とライセンスは [THIRD_PARTY_MODELS.md](THIRD_PARTY_MODELS.md) を参照してください。
