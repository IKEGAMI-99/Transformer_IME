# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書による高速かな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・文脈予測、端末内SQLiteを使ったPersonal RAG / 個人学習、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.10.3**

## APKダウンロード

### v0.10.3

[📱 Transformer IME v0.10.3 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32728401189/artifacts/9520505464)

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
- 通常音量は青〜紫中心、ピンク〜オレンジは大きなピーク時のみ
- Audio Pulseのリアルタイム値はプロセス内メモリで共有
- Pulse背景の高さは実際のキーボード高さへ追従
- 下部余白は実際のnavigation bar insetを使用し、固定44dp余白を廃止
- 通常キー面は透明で背景グローを直接表示
- パスワード欄ではAI・個人学習を停止
- `INTERNET` permissionなし
- 推論・学習・変換は端末内で完結

---

## v0.10.3

v0.10.3は実機フィードバックをもとに、**Audio Pulseのダイナミックレンジ、安定性、キーボード下部余白**を改善したバージョンです。

### Audio Pulseのピークを高く

v0.10.2では一般的な音楽でもピンク〜オレンジへ到達しやすかったため、v0.10.3では音量カーブを再調整しました。

```text
無音       黒
小〜中音量 青 / シアン
通常音量   シアン / 紫
大音量     紫 / ピンク
強いピーク ピンク / オレンジ
```

RMSはdBスケールへ変換し、peak値と組み合わせたあと非線形カーブを通します。描画側でもオレンジはvisual energyの上端付近に限定しています。

---

## Audio Pulseの安定性改善

v0.10.2ではAudioRecordの各バッファ処理ごとにSharedPreferencesへレベルを書き込み、IME側が繰り返し読み出していました。

v0.10.3では `AudioPulseState` を追加し、サービスとIME間のライブ音量を**プロセス内メモリだけで共有**します。

```text
AudioPlaybackCapture
        ↓
AudioRecord
        ↓
RMS + instantaneous peak
        ↓
AudioPulseState.level
        ↓
IME AudioPulseBackgroundView
```

これによりリアルタイム処理中の不要なPreference I/Oを削除しています。

さらにAudioRecordの生成・録音開始失敗を検出し、失敗時はPulse状態をリセットして安全に停止します。

IME側のPulse更新も約30fpsから**25fps（40ms間隔）**へ下げ、表示品質を大きく落とさず描画負荷を軽減しています。

---

## 下部余白

v0.10.2では44dpの固定minimum paddingがあり、一部端末でキーボード下部に大きな黒い余白ができていました。

v0.10.3では固定44dpを廃止し、**実際のnavigation bar inset + 8dp minimum**を利用します。

またAudio Pulse背景も固定320dpではなく、`keyboardContainer` の実測高さへ追従します。

```text
候補バー
────────────
キー領域       ← Pulse Viewも同じ高さ
キー領域
キー領域
────────────
必要最小限のnavigation inset
```

端末ごとのgesture areaが大きくても、それをそのまま余白として追加しません。

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
noise gate / nonlinear curve
      ↓
fast attack / slower decay
      ↓
bottom-up LinearGradient
+ bottom-center RadialGradient
```

無音時は黒で、通常キー面は透明です。音が入ったときだけ背景の光がキー越しに見えます。

音声そのものは保存しません。

### Audio Pulseの有効化

1. Transformer IMEアプリ本体を開く
2. `Audio Pulse 背景` をON
3. `RECORD_AUDIO` を許可
4. Androidの画面 / 音声キャプチャ確認を許可

再生アプリ側がAudioPlaybackCaptureを禁止している場合、そのアプリの音には反応しません。

---

## 数字キーパッド

日本語フリック右側の `123` を押すと、独立した4×4数字キーパッドへ切り替わります。

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

Zenzai v3.2-smallを1モデル搭載し、同一入力に対して最大10分岐を試します。

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

アプリ本体の `学習内容を表示` から内容・回数・最終使用時刻を確認できます。`学習内容をすべて削除` でローカル学習DBをリセットできます。

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
8. Process-local AudioPulseState
9. SharedPreferences hot-path書き込みがないこと
10. AudioRecord start failure guard
11. Pulse polling 40ms
12. 実測keyboard heightへのPulse View追従
13. compact navigation inset
14. Bottom-up LinearGradient / RadialGradient
15. 高いcolour headroom
16. Kotlin unit tests
17. Android NDK / arm64 build
18. APK内の単一GGUF + `libzenzjni.so`
19. APK artifact upload

---

## バージョン履歴

### v0.10.3

- Audio Pulseのピーク天井を引き上げ
- オレンジを上端ピーク付近に限定
- Process-local `AudioPulseState` を追加
- Audio Pulse hot pathからSharedPreferences I/Oを削除
- AudioRecord失敗時の安全停止を強化
- Pulse pollingを25fpsへ軽量化
- 固定44dp bottom paddingを廃止
- navigation bar inset + 8dp minimumへ変更
- Pulse背景を実測keyboard heightへ追従

### v0.10.2

- 無音時を黒へ
- 通常キー面を透明化
- 下端光源のLinearGradient + RadialGradient
- RMSのdBスケーリング
- silence gate追加

### v0.10.1

- 数字ファンを廃止
- `123` → 4×4数字キーパッド
- Audio Pulseをキー領域限定へ変更
- RMS + instantaneous peak

### v0.10.0

- Personal RAG
- 日本語 / 英語個人学習
- 学習内容ビューア
- 英語QWERTY候補
- Audio Pulse初版
- 絵文字パネル
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
