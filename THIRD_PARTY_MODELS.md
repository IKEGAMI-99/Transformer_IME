# Third-party models and components

Transformer IME v0.8 uses third-party open models/components. Raw user input is processed locally and is not sent to these projects or any server.

## zenz-v3.2-small-gguf

- Author / publisher: Keita Miwa (Miwa-Keita)
- Repository: `Miwa-Keita/zenz-v3.2-small-gguf`
- File used: `ggml-model-Q5_K_M.gguf`
- Approximate parameter count: 95.1M
- License: Apache License 2.0
- Purpose: primary neural kana-kanji conversion and next-input prediction model

The model is loaded locally in GGUF format using llama.cpp.

## zenz-v3.1-small-gguf

- Author / publisher: Keita Miwa (Miwa-Keita)
- Repository: `Miwa-Keita/zenz-v3.1-small-gguf`
- File used: `ggml-model-Q5_K_M.gguf`
- Approximate parameter count: 95.1M
- License: CC BY-SA 4.0
- Purpose: conditional second-opinion model when the primary model disagrees with the classical draft with low confidence

The original model and its derivatives remain subject to CC BY-SA 4.0. This project does not modify the model weights; it packages the published quantized GGUF file.

## Zenzai / AzooKeyKanaKanjiConverter

- Project: `azooKey/AzooKeyKanaKanjiConverter`
- License: MIT
- Purpose in this project: architecture/reference for the Zenzai prompt format and the design pattern of classical-draft + neural candidate verification.

Transformer IME contains an independent Android/Kotlin/C++ implementation. The Swift converter source is not vendored into this application.

## llama.cpp

- Project: `ggml-org/llama.cpp`
- Revision used for Android native build: `b4846`
- License: MIT
- Purpose: GGUF model loading and CPU inference on Android arm64

The revision is intentionally pinned for reproducible builds and because AzooKey's converter also documents this revision as a good small-batch baseline for Zenzai-style direct input.

## Mozc dictionary data

The existing Transformer IME dictionary builder uses open-source Mozc dictionary data. See the Mozc project and the generated dictionary metadata bundled with build artifacts for source/revision details.
