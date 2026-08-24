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
            text = "v0.7.0 · Predictive Mozc + Corpus Context + JP21M"
            textSize = 14f
            setPadding(0, 8.dp(), 0, 28.dp())
        })

        root.addView(TextView(this).apply {
            text = "1. Androidのキーボード設定で Transformer IME を有効にします。\n2. 入力方法から Transformer IME を選択します。\n\n日本語は黒基調の12キーフリック、英数はQWERTYです。v0.7では小文字入力を優先する修飾キーと、約2,115万parameterの日本語Transformerを搭載しました。入力内容は外部サーバーへ送信しません。"
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
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("ai_enabled", checked).apply() }
        }
        root.addView(aiSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp()).apply {
            topMargin = 24.dp()
        })

        root.addView(TextView(this).apply {
            text = "v0.7 構成\n" +
                "・小文字優先: つ→っ→づ、う→ぅ→ゔ\n" +
                "・通常変換: Mozc OSS由来SQLite辞書 + 読み前方一致予測\n" +
                "・次候補: Tatoeba日本語文由来の文脈DB + Tiny候補\n" +
                "・最終順位: 日本語学習済みJP21Mで再ランキング\n" +
                "・Japanese MoE: 約21.15M parameters / ${MediumMoETransformer.LAYERS} layers / ${MediumMoETransformer.EXPERTS} experts / hidden ${MediumMoETransformer.DIM}\n" +
                "・語彙hash 4096 / context ${MediumMoETransformer.CONTEXT_LENGTH}\n\n" +
                "Top-1 MoEなので総parameter数より実計算量を抑えています。"
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
