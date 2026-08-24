# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書によるかな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・候補再採点・文脈予測、端末内SQLiteを使ったPersonal RAG / 個人学習、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.11.0**

## APKダウンロード

### v0.11.0

[📱 Transformer IME v0.11.0 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32788568988/artifacts/9542391450)

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
- **10-wayニューラル生成 + 最大20候補の条件付き尤度スコアリング**
- Mozc / Personal RAG / 個人学習の候補順位をZenzaiスコアと融合
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
- 左下モード切替・右下Enterも他キーと同じフラット背景
- 候補タップ時、IME上部を `候補 wwww` がニコニコ動画風に流れる
- **Audio Pulse**: 無音時は黒、下端から音量連動グラデーション
- パスワード欄ではAI・個人学習・コメント演出を停止
- `INTERNET` permissionなし
- 推論・学習・変換は端末内で完結

---

## v0.11.0: constrained Zenzai precision reranker

v0.10系では、Zenzaiが10-way生成した自由生成候補の先頭をAI候補として強く採用していました。これはモデルが正しい表記を生成した場合は強い一方、Mozcがすでに正解候補を持っていても自由生成の妙な候補が前へ出る余地がありました。

v0.11ではZenzaiを**生成器だけでなく候補の審査員**として使います。

```text
読み + 左文脈
      ↓
Mozc / Personal RAG / 個人学習候補
      +
Zenzai 95M 10-way生成候補
      ↓
最大20候補へ整理
      ↓
Zenzaiで P(候補 | 文脈, 読み) を直接採点
      ↓
条件付き尤度 + Mozc/RAG/個人学習prior
      ↓
最終ランキング
      ↓
AI第1候補 → カタカナ → 通常候補
```

### 条件付き尤度スコア

JNI / llama.cpp側で各候補を強制的にtokenizeし、候補tokenごとにモデルのlog-softmaxを計算します。

```text
score(candidate)
  = Σ log P(token_i | prompt, token_<i)
    / token_length^0.65
```

候補長による極端な有利・不利を抑えるため、token数でlength normalizationしています。

### Mozc / RAGとの融合

- 既存のMozc / RAG / 個人学習候補を最大16件優先
- 10-way生成から新規候補を最大4件追加
- 合計最大20候補をZenzaiで直接採点
- 既存候補順位は小さなpriorとして保持
- 辞書にない自由生成候補には小さなpenaltyを与え、明確にモデルが支持した場合だけ上位へ移動

これにより、単純にモデルサイズを増やすのではなく、**Mozcの日本語辞書能力とZenzaiの文脈判断を分業**させています。

---

## v0.10.7: QWERTY layout stability + Katakana second

- 日本語 / QWERTYを**4行×60dp**へ統一
- モード切替時の不要な `requestApplyInsets()` を削除
- bottom safe insetが実際に変化した場合だけpaddingを更新
- AI候補の右隣をカタカナへ固定

```text
しんじゅくえき
      ↓
新宿駅  ✦
シンジュクエキ
新宿駅前
...
```

---

## v0.10.6: JNI Unicode crash fix

実機で確認された以下のnative crashを修正しました。

```text
JNI DETECTED ERROR IN APPLICATION:
input is not valid Modified UTF-8
in call to NewStringUTF
```

llama.cppの標準UTF-8 byte streamをJNIのModified UTF-8へ直接渡さず、明示的なUnicode bridgeを使用します。

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

- `NewStringUTF` / `GetStringUTFChars`依存を廃止
- standard UTF-8 ↔ Java UTF-16を明示変換
- 末尾の不完全UTF-8 code pointは安全に破棄
- 不正byteはU+FFFDへ置換
- emoji / surrogate pairにも対応
- 実機で確認された不完全UTF-8をCI regression fixtureとして保持

---

## IME lifecycle stability

- InputView再利用時もcandidate row / keyboard container / Pulse viewを保持
- Zenzai native runtimeをprocess-wide共有
- 全IME Serviceからのnative inferenceを直列化
- 古い入力のqueued inferenceをepochで破棄
- Mozc / RAG一時エラー時も入力候補を維持
- AudioRecord異常終了を安全処理
- navigation / system gesture / mandatory gesture Insetsを統合

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

## Personal RAG / 個人学習

端末内SQLiteへ以下を保存します。

- `conversion`: 読み → 選択した変換
- `next`: 文脈 → 次候補
- `memory`: 確定文章の文脈記憶
- `english`: prefix → 英単語
- `english_next`: 英文脈 → 次単語

頻度と最終使用時刻を使って候補順位を調整します。アプリ本体の `学習内容を表示` から確認でき、`学習内容をすべて削除` でリセットできます。

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
6. 10-way生成 + 最大20候補conditional likelihood scorer構成
7. candidate fusion unit tests
8. 左下モード / 右下EnterのフラットUI
9. QWERTY / 日本語の4×60dp固定レイアウト
10. AI候補直後のカタカナ固定
11. Unicode-safe JNI regression
12. InputView reuse lifecycle / stale inference guard
13. Audio Pulse failure handling
14. Kotlin unit tests
15. Android NDK arm64 build
16. APK内GGUF + `libzenzjni.so`確認
17. APK artifact upload

---

## バージョン履歴

### v0.11.0

- Zenzai 10-way生成を維持
- Mozc / RAG / 個人学習 / neural候補を最大20件へ統合
- Zenzai conditional likelihoodによる直接候補スコアリングを追加
- personalized candidate priorとneural scoreを融合
- unsupportedな自由生成候補にpenaltyを追加
- 左下モード切替 / 右下Enterの特別背景を撤去

### v0.10.7

- QWERTY / 日本語フリックを4×60dpへ統一
- QWERTY切替時の再レイアウトを抑制
- AI候補の右隣をカタカナへ変更

### v0.10.6

- Zenzai JNIのinvalid Modified UTF-8 abortを修正
- standard UTF-8 ↔ UTF-16 bridgeを実装
- 不完全な生成末尾を安全処理

### v0.10.5

- InputView再利用時の候補 / Audio Pulse切断を修正
- native inferenceをprocess-wide直列化
- stale推論破棄

### v0.10.4

- `変換` → `空白`
- Enterでひらがなのまま確定
- 左濁点 / 右半濁点
- NicoNico-style candidate comment

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
