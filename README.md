# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書によるかな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・文脈予測、端末内SQLiteを使ったPersonal RAG / 個人学習、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.10.6**

## APKダウンロード

### v0.10.6

[📱 Transformer IME v0.10.6 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32783357521/artifacts/9540648851)

GitHub Actions artifactはZIPで、その中に `app-debug.apk` が入っています。GitHubへのログインが必要な場合があります。

artifactには保存期限があります。期限切れの場合は [最新のAndroid Build](https://github.com/IKEGAMI-99/Transformer_IME/actions/workflows/android.yml) から最新成功ビルドを取得してください。

---

## 主な特徴

- Android `InputMethodService`
- 日本語: 12キー・5方向フリック
- 英語: QWERTY + 入力補完 + 次単語予測
- Mozc OSS由来SQLite辞書 + 読み途中の前方一致予測
- **Zenzai v3.2-small 約95.1M parameters / Q5_K_M**
- **1入力につき最大10-wayニューラル推論**
- AI第1候補 + 元の読みを第2候補に固定
- 確定後の文脈予測
- **Personal RAG / 個人学習**
- 学習内容の閲覧・全削除
- 絵文字パネル + 最近使った絵文字
- `123` → 4×4数字キーパッド
- Backspace長押し連続削除
- 日本語の旧 `変換` キーは **空白** キー
- Enterは候補未選択なら**ひらがなのまま確定**
- 修飾キーは **中央=小文字切替 / 左=濁点 / 右=半濁点**
- 候補タップ時、IME上部を `候補 wwww` がニコニコ動画風に流れる
- **Audio Pulse**: 無音時は黒、下端から音量連動グラデーション
- パスワード欄ではAI・個人学習・コメント演出を停止
- `INTERNET` permissionなし
- 推論・学習・変換は端末内で完結

---

## v0.10.6: JNI Unicode crash fix

v0.10.6では、実機で確認されたnative crashを修正しました。

Android tombstone:

```text
JNI DETECTED ERROR IN APPLICATION:
input is not valid Modified UTF-8
in call to NewStringUTF
from ZenzaiNative.nativeGenerateCandidates(...)
```

### 原因

llama.cppのtoken pieceは**標準UTF-8のbyte stream**です。

生成が `max_tokens` 境界で止まると、まれに1文字の途中で終了できます。

例:

```text
正常なUTF-8 ... E3 82 A2
途中切れ   ... EE B8
```

一方、JNIの `NewStringUTF` は標準UTF-8ではなく **Modified UTF-8** を要求します。

不完全なbyte列を `NewStringUTF` に渡すと、Android CheckJNIはJava例外ではなく**プロセスabort**を発生させる場合があります。

### v0.10.6の修正

JNI境界を明示的なUnicode変換へ変更しました。

```text
llama.cpp
standard UTF-8 bytes
      ↓
UTF-8 validator / decoder
      ↓
UTF-16
      ↓
JNI NewString
      ↓
Kotlin String
```

- `NewStringUTF` によるモデル出力変換を廃止
- `GetStringUTFChars` によるprompt変換も廃止
- standard UTF-8 → UTF-16 → `NewString`
- Java UTF-16 → standard UTF-8 を明示変換
- 末尾の不完全なUTF-8 code pointは安全に破棄
- 内部の不正byteはU+FFFDへ置換
- surrogate pairを処理するため絵文字など補助平面文字を含む文脈にも対応

CIでは、報告されたクラッシュと同型の `... EE B8` 不完全UTF-8を回帰fixtureとして保持しています。

また `zenz_jni.cpp` で実際の `env->NewStringUTF` / `env->GetStringUTFChars` 呼び出しが復活した場合、CIを失敗させます。

---

## v0.10.5: IME lifecycle stability

v0.10.5では、候補欄やAudio Pulseが途中から反応しなくなる問題を修正しました。

Androidは `InputMethodService` のInputViewを再利用することがあります。

従来は `onFinishInputView()` で以下のView参照を破棄していたため、見た目のキーボードだけ再利用され、内部接続が切れるケースがありました。

- candidate row
- keyboard container
- Audio Pulse background
- comment overlay

現在はViewを非表示にした際は更新だけ停止し、Service自体が破棄されるまで再利用可能な参照を保持します。

さらに:

- Zenzai native runtimeをprocess-wide共有
- 全IME Serviceからのnative inferenceを直列化
- 古い入力のqueued inferenceをepochで破棄
- Mozc / RAG一時エラー時も生のひらがな候補を維持
- AudioRecord異常終了を安全処理
- navigation / system gesture / mandatory gesture Insetsを統合

しています。

---

## 日本語入力

### Enter

入力中にEnterを押した場合、候補1位を自動採用しません。

```text
きょうは
  ↓ Enter
きょうは
```

漢字変換したい場合は候補バーを直接タップします。

### 空白

旧 `変換` キーは `空白` です。

入力中のひらがながある場合は、その読みをそのまま確定した後に空白を入力します。

### 小文字 / 濁点 / 半濁点

```text
        小文字
          ↑
濁点 ← ゛ 小 ゜ → 半濁点
```

中央タップは小文字優先サイクルです。

```text
つ → っ → づ
う → ぅ → ゔ
```

左フリックは直接濁点、右フリックは直接半濁点です。

---

## Zenzai 95M ×10

搭載モデル:

- `Miwa-Keita/zenz-v3.2-small-gguf`
- GPT-2 architecture
- 約95.1M parameters
- Q5_K_M
- Apache-2.0

同一入力に対して最大10分岐を生成し、Mozc候補・Personal RAG候補と統合します。

```text
左文脈 + 読み
      ↓
Mozc / Personal RAG candidates
      ↓
Zenzai 95M ×10
      ↓
AI候補
      ↓
元の読み
      ↓
通常候補
```

---

## Personal RAG / 個人学習

端末内SQLiteへ以下を保存します。

- `conversion`: 読み → 選択した変換
- `next`: 文脈 → 次候補
- `memory`: 確定文章の文脈記憶
- `english`: prefix → 英単語
- `english_next`: 英文脈 → 次単語

頻度と最終使用時刻を使って候補順位を調整します。

アプリ本体の `学習内容を表示` から確認でき、`学習内容をすべて削除` でリセットできます。

---

## Audio Pulse

Android `AudioPlaybackCapture` で取得可能なシステム再生音を解析します。

```text
system playback
      ↓
RMS + peak
      ↓
AudioPulseState
      ↓
fast attack / slow decay
      ↓
bottom glow
```

- 無音時は黒
- 小〜中音量は青 / 紫中心
- 大きなピークはピンク / オレンジ
- 音声そのものは保存しない
- live levelはprocess-local memoryで共有
- MediaProjection / AudioRecord停止時は安全終了

v0.10.6のクラッシュログではAudio Pulseのstack frameはなく、原因はZenzai JNIでした。そのためAudio Pulseは維持しています。

---

## NicoNico-style candidate comment

候補をタップするとIME上部を右から左へ流れます。

```text
新宿駅 wwww  → → →
```

- IME Window内のみ
- `SYSTEM_ALERT_WINDOW` 不使用
- パスワード欄では停止

---

## プライバシー

- `INTERNET` permissionなし
- 入力内容の外部送信なし
- ZenzaiモデルはAPKへ同梱
- Mozc辞書 / context DBもAPKへ同梱
- 個人学習DBは端末内SQLite
- `android:allowBackup="false"`
- パスワード欄ではAI・学習停止
- Audio Pulseの音声データは保存しない

Audio Pulseのみ以下のAndroid権限を使用します。

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
- JNI: `libzenzjni.so`
- Zenzai: Q5_K_M GGUF
- tokenizer/runtime: azooKey `llama.cpp b4846`

---

## CI

GitHub Actionsでは以下を自動検証します。

1. Mozc exact / prefix prediction
2. 日本語context prediction DB
3. Zenzai v3.2 GGUF検証
4. azooKey llama.cpp host build
5. Zenzai実かな漢字変換 smoke test
6. v0.10.6 Unicode-safe JNI source regression
7. 報告済み不完全UTF-8 crash fixture
8. InputView reuse lifecycle
9. process-wide serialized Zenzai runtime
10. stale inference guard
11. candidate fallback
12. Audio Pulse failure handling
13. HyperOS / gesture Insets
14. Kotlin unit tests
15. Android NDK arm64 build
16. APK内GGUF + `libzenzjni.so`確認
17. APK artifact upload

---

## バージョン履歴

### v0.10.6

- Zenzai JNIのinvalid Modified UTF-8 abortを修正
- standard UTF-8 ↔ UTF-16 bridgeを実装
- `NewStringUTF` / `GetStringUTFChars`依存を廃止
- 不完全な生成末尾を安全処理
- emoji / supplementary Unicode context対応を改善
- 実機クラッシュbyte列をCI regression fixtureへ追加

### v0.10.5

- InputView再利用時の候補 / Audio Pulse切断を修正
- native inferenceをprocess-wide直列化
- stale推論破棄
- raw hiragana fallback
- AudioRecord / MediaProjection failure handling
- gesture-safe bottom Insets

### v0.10.4

- `変換` → `空白`
- Enterでひらがなのまま確定
- 左濁点 / 右半濁点
- NicoNico-style candidate comment
- Bottom Glow改善

### v0.10.0

- Personal RAG
- 英語候補
- Audio Pulse
- emoji / numeric panel
- Backspace連続削除

---

## License

アプリ本体コードのライセンスはリポジトリ内のライセンス表記を参照してください。

Zenzaiモデル等の第三者コンポーネントについては `THIRD_PARTY_MODELS.md` を参照してください。
