# Transformer IME

Android向けの、**完全オンデバイスAI日本語IME**実験プロジェクトです。

Mozc OSS辞書による高速かな漢字変換を土台に、Zenzai v3.2-smallによるニューラル変換・文脈予測、端末内SQLiteを使ったPersonal RAG / 個人学習、英語入力予測、Audio Pulse UIを組み合わせています。

**現在のバージョン: v0.10.4**

## APKダウンロード

### v0.10.4

[📱 Transformer IME v0.10.4 APKをダウンロード](https://github.com/IKEGAMI-99/Transformer_IME/actions/runs/32732549820/artifacts/9522054849)

GitHub ActionsでZenzai実変換、source verification、unit test、Android NDK、APK生成まで検証済みのdebug APKです。

artifactはZIPとしてダウンロードされ、その中に `app-debug.apk` が入っています。GitHubへのログインが必要な場合があります。

GitHub Actions artifactには保存期限があります。期限切れの場合は [最新のAndroid Build](https://github.com/IKEGAMI-99/Transformer_IME/actions/workflows/android.yml) から最新成功ビルドを取得してください。

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
- `123` → 4×4数字キーパッド
- Backspace長押し連続削除
- 日本語の `変換` キーを **空白** キーへ変更
- Enterは候補未選択なら**ひらがなのまま確定**
- 修飾キーは **中央=小文字切替 / 左=濁点 / 右=半濁点**
- 変換候補タップ時、IME上部を `候補 wwww` がニコニコ動画風に流れる
- **Audio Pulse**: 無音時は黒、下端から連続グラデーションで発光
- 通常キー面は透明で背景グローを直接表示
- パスワード欄ではAI・個人学習・コメント演出を停止
- `INTERNET` permissionなし
- 推論・学習・変換は端末内で完結

---

## v0.10.4: 安定性改善

v0.10.4では、IMEの途中終了につながる可能性があったZenzai native runtimeのライフサイクルを見直しました。

従来は `InputMethodService` が破棄される際、別スレッドでllama.cpp推論が継続中でもモデルをcloseできる構造でした。

現在はZenzai 95Mを**Androidプロセス内で共有する1インスタンス**として保持します。

```text
Android process
   ↓
shared Zenzai 95M runtime
   ├ IME input view A
   ├ IME input view B
   └ async inference
```

IME Viewを閉じた際はPulse更新、コメントアニメーション、View参照を解除しますが、実行中のnative inferenceを途中で破棄しません。

executorへの投入もライフサイクル状態を確認してから行い、shutdown後に新しい推論を送らない構成です。

この変更はクラッシュ原因として疑わしい競合を除去するものですが、実機上での完全な安定性は継続検証中です。

---

## 日本語入力の操作

### Enter

変換候補をタップしていない状態でEnterを押した場合、候補1位を自動採用しません。

```text
きょうは
   ↓ Enter
きょうは
```

ひらがなのまま確定します。

漢字へ変換したい場合は候補バーから候補を直接選択します。

### 空白キー

旧 `変換` キーは `空白` に変更しました。

入力中のひらがながある場合は、そのひらがなをそのまま確定してからスペースを入力します。

### 濁点 / 半濁点 / 小文字

修飾キー:

```text
        小文字切替
            ↑
濁点  ←  ゛ 小 ゜  →  半濁点
```

中央タップは従来の小文字優先サイクルを維持します。

```text
つ → っ → づ
う → ぅ → ゔ
```

左フリックでは直接濁点、右フリックでは直接半濁点を適用します。

```text
か + 左 → が
は + 左 → ば
は + 右 → ぱ
ば + 右 → ぱ
```

---

## NicoNico-style candidate comment

日本語・次候補などの候補バーをタップすると、IME上部を選択語が右から左へ流れます。

```text
新宿駅 wwww   → → →
```

- 右から左へ約3.4秒で移動
- 白文字 + 黒シャドウ
- 2レーン
- `SYSTEM_ALERT_WINDOW` 不使用
- IMEウィンドウ内だけで表示
- パスワード欄では停止

システムオーバーレイ権限を使わないため、他アプリのWindowへ直接描画しません。

---

## Bottom Glow Audio Pulse

Audio PulseはAndroidの `AudioPlaybackCapture` で取得可能なシステム再生音を解析します。

```text
システム再生音
      ↓
AudioPlaybackCapture
      ↓
RMS + instantaneous peak
      ↓
AudioPulseState
      ↓
fast attack / slow decay
      ↓
Bottom-up gradient
```

### v0.10.4の描画

グラデーションの途中に横方向の切れ目が見えにくいよう、上方向のフェザーを長くし、5段階のLinearGradientに変更しました。

```text
上側    ほぼ透明
        ↓
        薄い色
        ↓
        中間色
        ↓
        強い色
下端    最も明るい光源
```

下中央のRadialGradientも重ねています。

無音時は黒です。通常音量は青〜紫中心で、ピンク〜オレンジは大きなピーク側に寄せています。

### 下部システムUI

IME下端はnavigation barとの重なりを避けつつ、大きな黒余白にならないよう調整しています。

- minimum safe inset: 18dp
- navigation bar insetを使用
- 一部OEMで異常に大きい値が返る場合は最大30dpへ制限
- navigation bar背景は黒

---

## Zenzai 95M ×10

搭載モデル:

- `Miwa-Keita/zenz-v3.2-small-gguf`
- GPT-2 architecture
- 約95.1M parameters
- Q5_K_M
- Apache-2.0

Zenzaiは1モデルだけ搭載し、同一入力に対して最大10分岐を試します。

```text
左文脈 + 読み
      ↓
Mozc candidate pool
      ↓
Zenzai 95M × 10 trials
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
- NicoコメントはIME Window内だけで描画

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
6. Zenzai 95M ×10
7. process-wide Zenzai runtime
8. IME lifecycle / executor guard
9. raw Enter
10. 日本語空白キー
11. 左濁点 / 右半濁点
12. NicoNico-style candidate comment
13. Bottom Glow 5-stop gradient
14. navigation inset制御
15. Kotlin unit tests
16. Android NDK / arm64 build
17. APK内の単一GGUF + `libzenzjni.so`
18. APK artifact upload

---

## バージョン履歴

### v0.10.4

- Zenzai runtimeをprocess-wide共有へ変更
- native inferenceとIME teardownのclose競合を回避
- InputView終了時のPulse / animation / View参照を解放
- `変換` → `空白`
- Enterでひらがなのまま確定
- 修飾キー: 中央=小文字 / 左=濁点 / 右=半濁点
- 候補選択時に `候補 wwww` コメント演出
- Bottom Glowの上方向フェザーを拡張
- 下部navigation UIとの重なりを調整

### v0.10.3

- Audio Pulse peak headroom改善
- Process-local `AudioPulseState`
- Audio Pulse hot pathからSharedPreferences I/Oを削除
- AudioRecord失敗時の安全停止
- Pulse pollingを25fpsへ軽量化

### v0.10.2

- 無音時を黒へ
- 通常キー面を透明化
- 下端光源のLinearGradient + RadialGradient

### v0.10.1

- 数字ファンを廃止
- `123` → 4×4数字キーパッド
- Audio Pulseをキー領域限定へ変更

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

### v0.8.x

- Zenzaiかな漢字変換導入
- ニューラル文脈予測

---

## Third-party models / licenses

第三者モデル・コンポーネントの詳細とライセンスは [THIRD_PARTY_MODELS.md](THIRD_PARTY_MODELS.md) を参照してください。
