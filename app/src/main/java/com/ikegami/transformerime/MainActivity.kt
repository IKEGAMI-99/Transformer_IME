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
import com.ikegami.transformerime.model.MediumMoETransformer

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
            text = "v0.5.0 · Dark Flick UI + Mozc + Context JP5M"
            textSize = 14f
            setPadding(0, 8.dp(), 0, 28.dp())
        })

        root.addView(TextView(this).apply {
            text = "1. Androidのキーボード設定で Transformer IME を有効にします。\n2. 入力方法から Transformer IME を選択します。\n\n日本語は黒基調の12キーフリック、英数はQWERTYです。変換確定後は直前の文章を見て次候補を自動表示します。入力内容は外部サーバーへ送信しません。"
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
            text = "Transformer候補ランキング / 次候補予測"
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
            text = "v0.5 構成\n" +
                "・日本語: Gboard系の5列配置を参考にした黒基調12キーフリック\n" +
                "・左列: Undo / カーソル左 / 記号 / 英数切替\n" +
                "・右列: Backspace / カーソル右 / 変換 / Enter\n" +
                "・通常変換: Mozc OSS由来SQLite辞書\n" +
                "・確定直後: Tinyで次候補を即表示 → JP5Mが直前96文字の文脈で再ランキング\n" +
                "・Japanese Medium MoE: 5,022,784 parameters / ${MediumMoETransformer.LAYERS} layers / ${MediumMoETransformer.EXPERTS} experts / hidden ${MediumMoETransformer.DIM}\n\n" +
                "次候補のJP5M再ランキングが終わると『✦次JP5M xxms』と表示します。"
            textSize = 14f
            setPadding(0, 18.dp(), 0, 0)
            setLineSpacing(0f, 1.15f)
        })

        root.addView(TextView(this).apply {
            text = "UIは一般的な日本語12キー配列とダーク配色を参考にした独自実装です。Google/Gboardのロゴ・画像・専用アセットは使用していません。"
            textSize = 12f
            setPadding(0, 20.dp(), 0, 0)
        })

        setContentView(root)
    }
}
