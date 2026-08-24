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
            text = "v0.1.0 · オンデバイス小型Transformer実験版"
            textSize = 14f
            setPadding(0, 8.dp(), 0, 28.dp())
        })

        root.addView(TextView(this).apply {
            text = "1. Androidのキーボード設定で Transformer IME を有効にします。\n2. 入力方法から Transformer IME を選択します。\n\n入力内容は外部サーバーへ送信しません。パスワード欄ではAI候補を停止します。"
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
            text = "Transformer候補ランキング"
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            isChecked = prefs.getBoolean("ai_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("ai_enabled", checked).apply()
            }
        }
        root.addView(aiSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp()).apply {
            topMargin = 24.dp()
        })

        root.addView(TextView(this).apply {
            text = "現在のモデル\nTiny Transformer PoC · 1 block · 3 heads · hidden 24 · context 12\n\nこのモデルは候補順位と次語予測の配線確認用です。モデルファイルを差し替えられる構造にしています。"
            textSize = 14f
            setPadding(0, 18.dp(), 0, 0)
        })

        setContentView(root)
    }
}
