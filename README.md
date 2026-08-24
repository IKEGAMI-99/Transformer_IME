# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書による高速なかな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・文脈予測、端末内SQLiteを使ったPersonal RAG / 個人学習、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.10.2**

## APKダウンロード

### v0.10.2

[📱 Transformer IME v0.10.2 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32726227402/artifacts/9519707673)

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
- **Audio Pulse**: 無音時は黒、音が入るとキー領域の下端から光が立ち上がる
- 音量に応じて青 / シアン → 紫 → ピンク → オレンジへ変化
- 通常キー面は透明で、背景グローを直接表示
- パスワード欄ではAI・個人学習を停止
- `INTERNET` permissionなし
- 推論・学習・変換は端末内で完結

---

## v0.10.2

v0.10.2は実機フィードバックをもとに、Audio Pulseのレイアウトと描画方式をもう一段作り直したバージョンです。

### Pulse領域を縮小

v0.10.1ではPulse背景Viewがキー領域より大きく測定される端末があり、IME下側に大きな発光領域ができることがありました。

v0.10.2ではPulse背景を**最大320dpのキー領域**に制限しています。

```text
候補バー
────────────
キー領域       ← Audio Pulseはここだけ
キー領域
キー領域
ナビゲーション
```

発光Viewが画面下部全体へ伸びないようにしています。

### 無音時は黒

Audio Pulseが有効でも、入力音量がしきい値以下なら背景は完全な黒です。

```text
silence
  ↓
noise gate
  ↓
energy = 0
  ↓
BLACK
```

通常キーの黒い塗りも撤去し、キー面はほぼ透明です。

そのため、無音時は黒いキーボード、音が入った時だけ背景の光がキー越しに見える構成になっています。

---

## Bottom Glow Audio Pulse

Audio PulseはAndroidの `AudioPlaybackCapture` で取得可能なシステム再生音を解析し、キー領域の**下端を光源**として発光します。

```text
システム再生音
      ↓
AudioPlaybackCapture
      ↓
PCM 16bit
      ↓
RMS → dB scale
+ instantaneous peak
      ↓
noise gate
      ↓
fast attack / slower decay
      ↓
bottom-up LinearGradient
+ bottom-center RadialGradient
```

### 音量スケーリング

v0.10.1の単純なRMS倍率では大きめの音で値が飽和しやすかったため、v0.10.2では**dBベース**に変更しました。

小さい音と大きい音の差を残しやすくし、普通の音量ですぐオレンジまで到達しないようにしています。

### 下から立ち上がるグラデーション

背景全体を単色で塗るのではなく、音量に応じて発光の高さが変わります。

```text
静音      █ 黒
小音量    █
          ░ 青
          ▓ シアン

中音量    ░
          ▒ 紫
          ▓ ピンク

大音量    ░
          ▒ ピンク
          █ オレンジ / 白いピーク
          ↑ 下端光源
```

LinearGradientに加えて、画面下側の外に光源があるように見せるRadialGradientを重ねています。

### 鼓動

RMSだけでなく瞬間ピークも監視しています。

急激な音量上昇ではbeat envelopeを生成して、短時間だけ発光を強くし、その後少しゆっくり減衰します。

色変化:

```text
小音量   青 / シアン
   ↓
中音量   紫 / バイオレット
   ↓
大音量   ピンク / マゼンタ
   ↓
ピーク   オレンジ寄り
```

音声そのものは保存しません。

### Audio Pulseの有効化

1. Transformer IMEアプリ本体を開く
2. `Audio Pulse 背景` をON
3. `RECORD_AUDIO` を許可
4. Androidの画面 / 音声キャプチャ確認を許可

再生アプリ側がAudioPlaybackCaptureを禁止している場合、そのアプリの音には反応しません。

---

## 数字キーパッド

v0.10.0の扇状数字ポップアップは廃止済みです。

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
7. 4×4数字キーパッド
8. Pulse背景の320dp制限
9. 通常キーが透明であること
10. Bottom-up LinearGradient / RadialGradient
11. Audio PulseのdBベースRMS処理
12. silence gate
13. instantaneous peak / beat envelope
14. Kotlin unit tests
15. Android NDK / arm64 build
16. APK内の単一GGUF + `libzenzjni.so`
17. APK artifact upload

---

## バージョン履歴

### v0.10.2

- Audio Pulse背景を最大320dpへ制限
- 無音時を完全な黒へ
- 通常キー面を透明化
- 下端光源のLinearGradient + RadialGradient
- RMSのdBスケーリング
- silence gate追加
- 音量色変化のダイナミックレンジ改善
- Zenzai 95M ×10 / Personal RAGは維持

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
