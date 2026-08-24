# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書による高速なかな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・文脈予測、端末内SQLiteを使ったPersonal RAG / 個人学習、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.10.1**

## APKダウンロード

### v0.10.1

[📱 Transformer IME v0.10.1 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32723081255/artifacts/9518564129)

GitHub ActionsでZenzai実変換、source verification、unit test、Android NDK、APK生成まで検証済みのdebug APKです。

artifactはZIPとしてダウンロードされ、その中に `app-debug.apk` が入っています。GitHubへのログインが必要な場合があります。

GitHub Actions artifactには保存期限があります。期限切れの場合は [最新のAndroid Build](https://github.com/IKEGAMI-99/Transformer_IME/actions/workflows/android.yml) から最新成功ビルドのAPKを取得してください。

---

## 主な特徴

- Android `InputMethodService`
- 日本語: 12キー・5方向フリック
- 英語: QWERTY + 入力補完 + 次単語予測
- Mozc OSS由来SQLite辞書 + 読み途中の前方一致予測
- **Zenzai v3.2-small 約95.1M parameters / Q5_K_M**
- **1入力につき最大10-wayニューラル推論**
- AI変換第1候補 + 元の読みを第2候補に固定
- 確定後の文脈予測
- **Personal RAG / 個人学習**
- 学習内容の閲覧・全削除
- 絵文字パネル + 最近使った絵文字
- `123` から**4×4数字キーパッド**へ切替
- Backspace長押し連続削除
- **Audio Pulse**: システム再生音に合わせてキー領域の背景が鼓動
- 音量に応じて背景色が変化
- パスワード欄ではAI・個人学習を停止
- `INTERNET` permissionなし
- 推論・学習・変換は端末内で完結

---

## v0.10.1

v0.10.1は実機フィードバックをもとに、数字UIとAudio Pulseを作り直したバージョンです。

### 数字キーパッド

v0.10.0の扇状数字ポップアップは廃止しました。

日本語フリック右側の `123` を押すと、そのまま独立した4×4数字キーパッドへ切り替わります。

```text
1    2    3    ⌫
4    5    6    -
7    8    9    /
かな  0    .    ↵
```

`かな` で日本語フリックへ戻ります。

Backspaceは数字レイヤーでも長押し連続削除に対応します。

---

## Audio Pulse v0.10.1

Audio PulseはAndroidの `AudioPlaybackCapture` で取得可能なシステム再生音を解析し、**キーボードのキー領域だけ**を音に合わせて発光させます。

v0.10.0ではIME全体の背景にエフェクトを置いていましたが、v0.10.1では候補バーより下のキーボード領域専用レイヤーへ変更しています。

```text
システム再生音
      ↓
AudioPlaybackCapture
      ↓
PCM 16bit
      ↓
RMS + instantaneous peak
      ↓
fast attack / slower decay envelope
      ↓
keyboard-only pulse background
```

### 色変化

音量に合わせて背景色が連続的に変化します。

```text
小音量   青 / シアン
   ↓
中音量   紫 / バイオレット
   ↓
大音量   ピンク / マゼンタ
   ↓
ピーク   オレンジ寄り
```

音量の急な立ち上がりを検出すると短いbeat envelopeを作り、グロー半径と明るさを瞬間的に大きくして、その後ゆっくり減衰させます。

音声そのものは保存しません。

### Audio Pulseの有効化

1. Transformer IMEアプリ本体を開く
2. `Audio Pulse 背景` をON
3. `RECORD_AUDIO` を許可
4. Androidの画面 / 音声キャプチャ確認を許可

再生アプリ側がAudioPlaybackCaptureを禁止している場合、そのアプリの音には反応しません。

---

## 日本語かな漢字変換

Mozc OSS dictionaryをビルド時にAndroid向けSQLiteへ再構成しています。

```text
うめだ         → 梅田
しんじゅくえき → 新宿駅
はっとり       → 服部
```

prefix indexも持つため、読みを最後まで入力する前から候補を出せます。

`゛゜大小` は小文字が存在する場合、小文字を優先します。

```text
つ → っ → づ
う → ぅ → ゔ
```

---

## Zenzai 95M ×10

搭載モデル:

- `Miwa-Keita/zenz-v3.2-small-gguf`
- GPT-2 architecture
- 約95.1M parameters
- Q5_K_M
- Apache-2.0

v0.8系の2モデル構成は廃止し、現在はZenzai v3.2-smallを1モデルだけ搭載しています。

その代わり、同一入力に対して最大10分岐を試します。

```text
左文脈 + 読み
      ↓
Mozc candidate pool
      ↓
Zenzai 95M
 ├ trial 1
 ├ trial 2
 ├ trial 3
 ├ ...
 └ trial 10
      ↓
AI第1候補
      ↓
元の読み
      ↓
通常候補
```

候補バーでは `✦Z95×10` 系の表示でZenzai推論結果を識別できます。

---

## Personal RAG / 個人学習

ユーザーが実際に使った変換や文脈続きを端末内SQLiteへ記録します。

保存する情報:

- `conversion`: 読み → 選択した変換
- `next`: 文脈 → 選択した次候補
- `memory`: 普通に確定した文章の続きをRAG記憶として保存
- `english`: prefix → 選択した英単語
- `english_next`: 英文脈 → 次単語

```text
現在の読み / 文脈
      ↓
Personal RAG
      ↓
過去によく使った候補
      ↓
Mozc / context DB候補と統合
      ↓
Zenzai rerank
```

頻度と最終使用時刻を使って順位を調整します。

アプリ本体の `学習内容を表示` から内容・回数・最終使用時刻を確認できます。

`学習内容をすべて削除` でローカル学習DBをリセットできます。

---

## 英語QWERTY

英語モードでも候補バーを利用できます。

- prefix補完
- 単語候補
- 次単語予測
- 英語個人学習
- 英語Personal RAG
- Shift
- Backspace長押し連続削除

外部APIは使用しません。

---

## プライバシー / セキュリティ

- `INTERNET` permissionなし
- 入力内容の外部送信なし
- ZenzaiモデルはAPKへ同梱
- Mozc辞書 / context DBもAPKへ同梱
- 個人学習DBは端末内SQLiteのみ
- `android:allowBackup="false"`
- パスワード欄ではAI候補生成停止
- パスワード欄では個人学習停止
- Audio Pulseの音声データは保存しない

Audio Pulse用権限:

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`

---

## Android / Native runtime

- minSdk: **31**
- targetSdk: **36**
- ABI: **arm64-v8a**
- Java: **17**
- NDK: **27.2.12479018**
- GGUF Q5_K_M
- JNI native runtime: `libzenzjni.so`
- azooKey Zenzai tokenizer対応 `llama.cpp b4846`

---

## GitHub Actions

CIでは以下を自動検証します。

1. Mozc辞書 / prefix予測
2. 日本語context prediction DB
3. Zenzai v3.2-small GGUF
4. azooKey llama.cpp host build
5. Zenzai実かな漢字変換 smoke test
6. Zenzai 95M ×10構成
7. 扇状数字UIが削除されていること
8. 4×4数字キーパッドのsource verification
9. keyboard-only Audio Pulse実装
10. RMS + instantaneous peak処理
11. Audio Pulseの色変化実装
12. Kotlin unit tests
13. Android NDK / arm64 build
14. APK内の単一GGUF + `libzenzjni.so`
15. APK artifact upload

---

## バージョン履歴

### v0.10.1

- 数字ファンを廃止
- `123` → 4×4数字キーパッド
- Audio Pulseをキー領域限定へ変更
- RMS + instantaneous peak
- beat envelope強化
- 音量による青→紫→ピンク→オレンジ色変化

### v0.10.0

- Personal RAG
- 日本語 / 英語個人学習
- 学習内容ビューア
- 英語QWERTY候補
- Audio Pulse初版
- 絵文字パネル
- 数字 / 記号パネル
- Backspace長押し連続削除

### v0.9.x

- Zenzai v3.2-small単一モデル化
- 95.1M parameters
- 10-wayニューラル推論
- ローカル個人学習

### v0.8.x

- Zenzaiかな漢字変換導入
- ニューラル文脈予測

---

## Third-party models / licenses

第三者モデル・コンポーネントの詳細とライセンスは [THIRD_PARTY_MODELS.md](THIRD_PARTY_MODELS.md) を参照してください。
