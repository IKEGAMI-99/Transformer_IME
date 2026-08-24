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
            text = "v0.6.0 · Predictive Mozc + Corpus Context + JP5M"
            textSize = 14f
            setPadding(0, 8.dp(), 0, 28.dp())
        })

        root.addView(TextView(this).apply {
            text = "1. Androidのキーボード設定で Transformer IME を有効にします。\n2. 入力方法から Transformer IME を選択します。\n\n日本語は黒基調の12キーフリック、英数はQWERTYです。v0.6では読み途中の変換予測と、実日本語コーパス由来の次候補を追加しました。入力内容は外部サーバーへ送信しません。"
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
            text = "v0.6 構成\n" +
                "・日本語: 5列配置の黒基調12キーフリック\n" +
                "・『や』: 上=ゆ / 下=よ / 左右=括弧\n" +
                "・通常変換: Mozc OSS由来SQLite辞書 + 読み前方一致の変換予測\n" +
                "・次候補: Tatoeba日本語文から作った文脈→続きDB + Tiny候補\n" +
                "・最終順位: 日本語学習済みJP5Mが直前文脈で再ランキング\n" +
                "・Japanese Medium MoE: 5,022,784 parameters / ${MediumMoETransformer.LAYERS} layers / ${MediumMoETransformer.EXPERTS} experts / hidden ${MediumMoETransformer.DIM}\n\n" +
                "候補生成を先に強化し、その候補をTransformerが選ぶハイブリッド構成です。"
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
