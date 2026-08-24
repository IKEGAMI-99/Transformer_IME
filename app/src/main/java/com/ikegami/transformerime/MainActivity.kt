package com.ikegami.transformerime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.ikegami.transformerime.audio.AudioPulseService
import com.ikegami.transformerime.learning.UserLearningStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private lateinit var audioSwitch: Switch
    private val prefs by lazy { getSharedPreferences("transformer_ime", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 32.dp(), 24.dp(), 32.dp())
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply { text = "Transformer IME"; textSize = 30f })
        root.addView(TextView(this).apply {
            text = "v0.10.7 · QWERTY stability + Katakana second"
            textSize = 14f
            setPadding(0, 8.dp(), 0, 22.dp())
        })
        root.addView(TextView(this).apply {
            text = "日本語はフリック、英語はQWERTY。Mozc辞書・Zenzai・端末内の個人学習を組み合わせます。入力内容やAudio Pulseの音声データは外部送信しません。"
            textSize = 16f
            setLineSpacing(0f, 1.18f)
        })

        root.addView(Button(this).apply {
            text = "キーボード設定を開く"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }, fullButton(22))
        root.addView(Button(this).apply {
            text = "入力方法を選択"
            setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        }, fullButton(10))

        root.addView(Switch(this).apply {
            text = "Zenzai変換 / 文脈予測"
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            isChecked = prefs.getBoolean("ai_enabled", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("ai_enabled", checked).apply() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp()).apply { topMargin = 20.dp() })

        audioSwitch = Switch(this).apply {
            text = "Audio Pulse 背景"
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            isChecked = prefs.getBoolean(AudioPulseService.KEY_ENABLED, false)
            setOnCheckedChangeListener { _, checked -> if (checked) requestAudioPulse() else disableAudioPulse() }
        }
        root.addView(audioSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp()))

        root.addView(TextView(this).apply {
            text = "v0.10.7ではQWERTYと日本語フリックの行高を統一し、モード切替時の不要なInset再適用を削減しました。AI変換候補の右隣は、ひらがな原文ではなくカタカナ表記を固定表示します。v0.10.6のUnicode-safe JNI安定化はそのまま維持しています。"
            textSize = 13f
            setPadding(0, 0, 0, 16.dp())
        })

        root.addView(Button(this).apply {
            text = "学習内容を表示"
            setOnClickListener { showLearningData() }
        }, fullButton(8))
        root.addView(Button(this).apply {
            text = "学習内容をすべて削除"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("個人学習を削除")
                    .setMessage("変換・次候補・英語・RAG用の学習履歴をすべて削除します。")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("削除") { _, _ -> UserLearningStore(this@MainActivity).use { it.clearAll() } }
                    .show()
            }
        }, fullButton(8))

        root.addView(TextView(this).apply {
            text = "v0.10.7 構成\n" +
                "・Zenzai v3.2-small Q5_K_M 約95.1M / 10試行\n" +
                "・AI第1候補 → カタカナ → 通常候補\n" +
                "・QWERTY / 日本語フリックを4行×60dpへ統一\n" +
                "・QWERTY切替時の不要なrequestApplyInsetsを削除\n" +
                "・bottom insetが変化した時だけレイアウト更新\n" +
                "・Unicode-safe JNI / native推論直列化 / stale推論破棄を維持\n" +
                "・InputView再利用 / 候補フォールバック / Audio Pulse安定化を維持\n" +
                "・日本語Enter / 空白キー / 濁点フリック / ニコニコ風コメントを維持"
            textSize = 14f
            setPadding(0, 20.dp(), 0, 0)
            setLineSpacing(0f, 1.15f)
        })

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::audioSwitch.isInitialized) {
            setAudioSwitchWithoutCallback(prefs.getBoolean(AudioPulseService.KEY_ENABLED, false))
        }
    }

    private fun fullButton(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 56.dp()
    ).apply { topMargin = top.dp() }

    private fun requestAudioPulse() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    private fun disableAudioPulse() {
        prefs.edit().putBoolean(AudioPulseService.KEY_ENABLED, false).apply()
        startService(Intent(this, AudioPulseService::class.java).setAction(AudioPulseService.ACTION_STOP))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestAudioPulse()
            else setAudioSwitchWithoutCallback(false)
        }
    }

    @Deprecated("MediaProjection still returns through activity result on the supported minSdk")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MEDIA_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            prefs.edit().putBoolean(AudioPulseService.KEY_ENABLED, false).apply()
            setAudioSwitchWithoutCallback(false)
            return
        }
        prefs.edit().putBoolean(AudioPulseService.KEY_ENABLED, true).apply()
        val service = Intent(this, AudioPulseService::class.java)
            .putExtra(AudioPulseService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(AudioPulseService.EXTRA_RESULT_DATA, data)
        startForegroundService(service)
    }

    private fun setAudioSwitchWithoutCallback(value: Boolean) {
        audioSwitch.setOnCheckedChangeListener(null)
        audioSwitch.isChecked = value
        audioSwitch.setOnCheckedChangeListener { _, checked -> if (checked) requestAudioPulse() else disableAudioPulse() }
    }

    private fun showLearningData() {
        val store = UserLearningStore(this)
        val entries = store.listEntries(350)
        store.close()
        val formatter = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        val text = if (entries.isEmpty()) "まだ学習データはありません。" else entries.joinToString("\n\n") { e ->
            val kind = when (e.kind) {
                UserLearningStore.KIND_CONVERSION -> "変換"
                UserLearningStore.KIND_NEXT -> "次候補"
                UserLearningStore.KIND_MEMORY -> "RAG記憶"
                UserLearningStore.KIND_ENGLISH -> "英語"
                UserLearningStore.KIND_ENGLISH_NEXT -> "英語次候補"
                else -> e.kind
            }
            "[$kind] ${e.keyText} → ${e.surface}\n選択 ${e.useCount}回 · ${formatter.format(Date(e.lastUsed))}"
        }
        val view = TextView(this).apply {
            this.text = text; textSize = 14f; setPadding(20.dp(), 16.dp(), 20.dp(), 16.dp()); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("学習内容 ${entries.size}件")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("閉じる", null)
            .show()
    }

    companion object {
        private const val REQ_AUDIO_PERMISSION = 701
        private const val REQ_MEDIA_PROJECTION = 702
    }
}
