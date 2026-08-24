package com.ikegami.transformerime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("transformer_ime", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 32.dp(), 24.dp(), 24.dp())
        }

        root.addView(TextView(this).apply {
            text = "Transformer IME"
            textSize = 30f
        })
        root.addView(TextView(this).apply {
            text = "v0.8.0 · Mozc draft + Zenzai ~190M cascade"
            textSize = 14f
            setPadding(0, 8.dp(), 0, 28.dp())
        })

        root.addView(TextView(this).apply {
            text = "1. Androidのキーボード設定で Transformer IME を有効にします。\n2. 入力方法から Transformer IME を選択します。\n\n日本語は黒基調の12キーフリック、英数はQWERTYです。v0.8ではazooKey/Zenzaiの方式を参考に、かな漢字変換専用のZenzモデルをオンデバイスで使います。入力内容は外部サーバーへ送信しません。"
            textSize = 17f
            setLineSpacing(0f, 1.2f)
        })

        root.addView(Button(this).apply {
            text = "キーボード設定を開く"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dp()).apply {
            topMargin = 28.dp()
        })

        root.addView(Button(this).apply {
            text = "入力方法を選択"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dp()).apply {
            topMargin = 10.dp()
        })

        val aiSwitch = Switch(this).apply {
            text = "Zenzai変換 / 文脈予測"
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            isChecked = prefs.getBoolean("ai_enabled", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("ai_enabled", checked).apply() }
        }
        root.addView(aiSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp()).apply {
            topMargin = 24.dp()
        })

        root.addView(TextView(this).apply {
            text = "v0.8 構成\n" +
                "・小文字優先: つ→っ→づ、う→ぅ→ゔ\n" +
                "・ドラフト変換: Mozc OSS由来SQLite辞書 + ビーム探索\n" +
                "・Primary: zenz-v3.2-small Q5_K_M 約95.1M parameters\n" +
                "・Second opinion: zenz-v3.1-small Q5_K_M 約95.1M parameters\n" +
                "・合計: 約190.2M parameters（通常はPrimaryだけ推論）\n" +
                "・Zenzai v3形式: 左文脈 + 読み + 出力を直接モデル評価\n" +
                "・弱い/不一致時だけ2モデル目を使うカスケード\n" +
                "・推論: llama.cpp / arm64 / GGUF Q5_K_M\n\n" +
                "辞書を捨てずにドラフトとして使い、ニューラル変換で検証・補正する構成です。"
            textSize = 14f
            setPadding(0, 18.dp(), 0, 0)
            setLineSpacing(0f, 1.15f)
        })

        root.addView(TextView(this).apply {
            text = "Zenzai/Zenzの設計を参考にしたAndroid実装です。モデル・辞書・llama.cppの各ライセンス情報はリポジトリ内のTHIRD_PARTY_MODELS.mdを参照してください。"
            textSize = 12f
            setPadding(0, 20.dp(), 0, 0)
        })

        setContentView(root)
    }
}
