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
            text = "v0.4.0 · フリック入力 + Mozc辞書 + JP5M"
            textSize = 14f
            setPadding(0, 8.dp(), 0, 28.dp())
        })

        root.addView(TextView(this).apply {
            text = "1. Androidのキーボード設定で Transformer IME を有効にします。\n2. 入力方法から Transformer IME を選択します。\n\n日本語は12キーのフリック入力、英数モードはQWERTY入力です。入力内容は外部サーバーへ送信しません。パスワード欄ではAI候補を停止します。"
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
            text = "v0.4 入力・変換構成\n" +
                "・日本語: 12キー5方向フリック + 濁点/半濁点/小文字キー\n" +
                "・英語: QWERTY + Shift\n" +
                "・通常変換: Mozc OSS辞書をCIで小型SQLiteへ変換し、地名・人名を含む候補をオンデバイス検索\n" +
                "・Tiny Transformer: 即時候補と次語予測\n" +
                "・Japanese Medium MoE: 5,022,784 parameters / ${MediumMoETransformer.LAYERS} layers / ${MediumMoETransformer.EXPERTS} experts / hidden ${MediumMoETransformer.DIM}\n" +
                "・Tatoeba日本語文コーパスで次文字予測学習\n\n" +
                "日本語学習済み5Mモデルが動作すると候補先頭に『✦JP5M』、拡張辞書もロード済みなら『·D』を付けます。"
            textSize = 14f
            setPadding(0, 18.dp(), 0, 0)
            setLineSpacing(0f, 1.15f)
        })

        root.addView(TextView(this).apply {
            text = "Tatoeba Project contributors · CC BY 2.0 FR。Mozc OSS dictionaryにはIPAdic等のOSS/公開データが含まれます。詳細な著作権表示はREADMEを参照してください。"
            textSize = 12f
            setPadding(0, 20.dp(), 0, 0)
        })

        setContentView(root)
    }
}
