# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書によるかな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・文脈予測、端末内SQLiteを使ったPersonal RAG / 個人学習、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.10.7**

## APKダウンロード

### v0.10.7

[📱 Transformer IME v0.10.7 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32786472160/artifacts/9541717156)

GitHub Actions artifactはZIPで、その中に `app-debug.apk` が入っています。GitHubへのログインが必要な場合があります。

artifactには保存期限があります。期限切れの場合は [最新のAndroid Build](https://github.com/IKEGAMI-99/Transformer_IME/actions/workflows/android.yml) から最新成功ビルドを取得してください。

---

## 主な特徴

- Android `InputMethodService`
- 日本語: 12キー・5方向フリック
- 英語: QWERTY + 入力補完 + 次単語予測
- 日本語 / QWERTYともに4行×60dpの固定キーボードボディ
- Mozc OSS由来SQLite辞書 + 読み途中の前方一致予測
- **Zenzai v3.2-small 約95.1M parameters / Q5_K_M**
- **1入力につき最大10-wayニューラル推論**
- 日本語候補は **AI第1候補 → カタカナ → 通常候補**
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

## v0.10.7: QWERTY layout stability + Katakana second

v0.10.7では、英語QWERTYへ切り替えた直後に下部へ隙間ができ、最初の文字入力でキーボード位置が変わる問題を修正しました。

### QWERTYの位置ズレ対策

従来は日本語フリックが1行60dp、QWERTYが1行55dpだったため、モード切替だけでキーボード本体の高さが変化していました。

さらにモード切替ごとに `requestApplyInsets()` を実行していたため、HyperOSなどで遅れてもう一度Insetsが配送され、最初のキー入力付近で再レイアウトされる可能性がありました。

v0.10.7では:

- 日本語 / QWERTYを**4行×60dp**へ統一
- モード切替時の不要な `requestApplyInsets()` を削除
- bottom safe insetが実際に変化した場合だけpaddingを更新
- キーボード再構築後は通常の `requestLayout()` のみ使用

としています。

### カタカナ第2候補

AI変換が有効な日本語入力では、AI候補の右隣を元のひらがなではなくカタカナへ固定します。

```text
しんじゅくえき
      ↓
新宿駅  ✦Z95×10
シンジュクエキ
新宿駅前
新宿駅構内
...
```

元のひらがなはMozc等の通常候補として後方へ残る場合があります。

---

## v0.10.6: JNI Unicode crash fix

v0.10.6では、実機で確認されたnative crashを修正しました。

```text
JNI DETECTED ERROR IN APPLICATION:
input is not valid Modified UTF-8
in call to NewStringUTF
from ZenzaiNative.nativeGenerateCandidates(...)
```

llama.cppのtoken pieceは標準UTF-8 byte streamであり、生成が `max_tokens` 境界で止まると1文字の途中で終了する場合があります。Android JNIの `NewStringUTF` はModified UTF-8を要求するため、不完全なbyte列を渡すとCheckJNIがプロセスをabortするケースがありました。

現在は:

```text
llama.cpp standard UTF-8
      ↓
UTF-8 validator / decoder
      ↓
UTF-16
      ↓
JNI NewString
      ↓
Kotlin String
```

という明示的なUnicode bridgeを使用します。

- `NewStringUTF` によるモデル出力変換を廃止
- `GetStringUTFChars` によるprompt変換も廃止
- standard UTF-8 → UTF-16 → `NewString`
- Java UTF-16 → standard UTF-8
- 末尾の不完全UTF-8 code pointは安全に破棄
- 不正な内部byteはU+FFFDへ置換
- emoji / surrogate pairにも対応
- 実機で確認された `... EE B8` 途中切れをCI regression fixtureとして保持

---

## v0.10.5: IME lifecycle stability

Androidは `InputMethodService` のInputViewを再利用する場合があります。以前は `onFinishInputView()` で候補欄やAudio PulseへのView参照まで破棄し、見た目のViewだけ再利用されるケースがありました。

現在は:

- InputView再利用時もcandidate row / keyboard container / Pulse viewを保持
- Zenzai native runtimeをprocess-wide共有
- 全IME Serviceからのnative inferenceを直列化
- 古い入力のqueued inferenceをepochで破棄
- Mozc / RAG一時エラー時も入力候補を維持
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

旧 `変換` キーは `空白` です。入力中の読みがある場合は、その読みをそのまま確定した後に空白を入力します。

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

現在の10-wayは最初の生成tokenを複数方向へ分岐し、それぞれを生成してMozc / Personal RAG候補と統合します。

```text
左文脈 + 読み
      ↓
Mozc / Personal RAG candidates
      ↓
Zenzai 95M ×10
      ↓
AI候補
      ↓
カタカナ
      ↓
通常候補
```

今後の精度改善候補として、Mozc draftをZenzaiのconditional likelihoodで直接採点するneural rescoringや、複数tokenにまたがるbeam searchを検討しています。

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
6. QWERTY / 日本語の4×60dp固定レイアウト
7. モード切替時の不要なInsets再要求がないこと
8. AI候補直後のカタカナ固定
9. Unicode-safe JNI regression
10. 不完全UTF-8 crash fixture
11. InputView reuse lifecycle
12. process-wide serialized Zenzai runtime
13. stale inference guard
14. candidate fallback
15. Audio Pulse failure handling
16. HyperOS / gesture Insets
17. Kotlin unit tests
18. Android NDK arm64 build
19. APK内GGUF + `libzenzjni.so`確認
20. APK artifact upload

---

## バージョン履歴

### v0.10.7

- QWERTY / 日本語フリックを4×60dpへ統一
- QWERTY切替直後の下部隙間 / 初回入力時の再レイアウトを抑制
- mode switch時の不要な `requestApplyInsets()` を削除
- bottom inset変化時だけpaddingを更新
- AI候補の右隣をカタカナへ変更

### v0.10.6

- Zenzai JNIのinvalid Modified UTF-8 abortを修正
- standard UTF-8 ↔ UTF-16 bridgeを実装
- `NewStringUTF` / `GetStringUTFChars`依存を廃止
- 不完全な生成末尾を安全処理
- emoji / supplementary Unicode context対応を改善

### v0.10.5

- InputView再利用時の候補 / Audio Pulse切断を修正
- native inferenceをprocess-wide直列化
- stale推論破棄
- candidate fallback
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
