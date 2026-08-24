package com.ikegami.transformerime.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.ikegami.transformerime.conversion.CandidateGenerator
import com.ikegami.transformerime.conversion.RomajiConverter
import com.ikegami.transformerime.model.MediumMoETransformer
import com.ikegami.transformerime.model.ModelRepository
import com.ikegami.transformerime.model.TinyTransformerModel
import java.util.concurrent.Executors

class TransformerImeService : InputMethodService() {
    private val romanBuffer = StringBuilder()
    private var compositionContext = ""
    private var candidateRow: LinearLayout? = null
    private var japaneseMode = true
    private var secureField = false
    private var aiEnabledByUser = true
    private var model: TinyTransformerModel? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var mediumModel: MediumMoETransformer? = null
    private var pendingMediumRerank: Runnable? = null
    private var candidateEpoch = 0
    private var currentReading = ""
    private var currentCandidates: List<String> = emptyList()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        model = ModelRepository.get(this)
        inferenceExecutor.execute {
            mediumModel = runCatching { MediumMoETransformer.create() }.getOrNull()
        }
    }

    override fun onDestroy() {
        pendingMediumRerank?.let(mainHandler::removeCallbacks)
        inferenceExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cancelPendingRerank()
        romanBuffer.clear()
        compositionContext = ""
        currentReading = ""
        currentCandidates = emptyList()
        secureField = attribute?.let(::isPasswordField) ?: false
        aiEnabledByUser = getSharedPreferences("transformer_ime", Context.MODE_PRIVATE)
            .getBoolean("ai_enabled", true)
    }

    override fun onFinishInput() {
        cancelPendingRerank()
        romanBuffer.clear()
        compositionContext = ""
        currentReading = ""
        currentCandidates = emptyList()
        candidateRow?.removeAllViews()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp(), 4.dp(), 4.dp(), 6.dp())
            setBackgroundColor(Color.rgb(245, 245, 245))
            setOnApplyWindowInsetsListener { view, insets ->
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                view.setPadding(4.dp(), 4.dp(), 4.dp(), nav.bottom + 8.dp())
                insets
            }
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp(), 0, 4.dp(), 0)
        }
        scroll.addView(candidateRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            50.dp()
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            50.dp()
        ))

        addLetterRow(root, listOf("q","w","e","r","t","y","u","i","o","p"))
        addLetterRow(root, listOf("a","s","d","f","g","h","j","k","l"), horizontalPadding = 13)

        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        third.addView(keyButton(if (japaneseMode) "英数" else "かな", 1.35f) { toggleMode(it as Button) })
        listOf("z","x","c","v","b","n","m").forEach { letter ->
            third.addView(keyButton(letter, 1f) { handleLetter(letter) })
        }
        third.addView(keyButton("⌫", 1.35f) { handleBackspace() })
        root.addView(third, rowParams())

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton("、", 1f) { handlePunctuation("、") })
        bottom.addView(keyButton("。", 1f) { handlePunctuation("。") })
        bottom.addView(keyButton("変換 / 空白", 3.2f) { handleSpace() })
        bottom.addView(keyButton("⏎", 1.25f) { handleEnter() })
        root.addView(bottom, rowParams())

        root.requestApplyInsets()
        postNextPredictions()
        return root
    }

    private fun addLetterRow(root: LinearLayout, letters: List<String>, horizontalPadding: Int = 0) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalPadding.dp(), 0, horizontalPadding.dp(), 0)
        }
        letters.forEach { letter -> row.addView(keyButton(letter, 1f) { handleLetter(letter) }) }
        root.addView(row, rowParams())
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        55.dp()
    ).apply { topMargin = 3.dp() }

    private fun keyButton(label: String, weight: Float, action: (View) -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = if (label.length > 4) 12f else 18f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(1.dp(), 0, 1.dp(), 0)
            setOnClickListener(action)
            background = GradientDrawable().apply {
                cornerRadius = 7.dp().toFloat()
                setColor(Color.WHITE)
                setStroke(1.dp(), Color.rgb(220, 220, 220))
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = 2.dp()
                marginEnd = 2.dp()
            }
        }
    }

    private fun handleLetter(letter: String) {
        if (!japaneseMode) {
            currentInputConnection?.commitText(letter, 1)
            postNextPredictions()
            return
        }
        if (romanBuffer.isEmpty()) compositionContext = textBeforeCursor()
        romanBuffer.append(letter)
        refreshCompositionAndCandidates()
    }

    private fun refreshCompositionAndCandidates() {
        val reading = RomajiConverter.convert(romanBuffer.toString())
        currentReading = reading
        if (reading.isEmpty()) {
            currentCandidates = emptyList()
            currentInputConnection?.finishComposingText()
            postNextPredictions()
            return
        }

        currentInputConnection?.setComposingText(reading, 1)
        val base = CandidateGenerator.candidates(reading)
        val tinyRanked = if (aiActive()) model?.rankCandidates(compositionContext, base) ?: base else base
        currentCandidates = tinyRanked
        showCandidates(tinyRanked, if (aiActive() && tinyRanked.isNotEmpty()) "✦" else null) { commitCandidate(it) }
        scheduleMediumRerank(reading, tinyRanked)
    }

    private fun scheduleMediumRerank(reading: String, candidates: List<String>) {
        cancelPendingRerank(incrementEpoch = false)
        if (!aiActive() || secureField || candidates.size <= 1) return
        val medium = mediumModel ?: return
        val epoch = ++candidateEpoch
        val contextSnapshot = compositionContext
        val candidatesSnapshot = candidates.toList()

        val runnable = Runnable {
            if (epoch != candidateEpoch || currentReading != reading || romanBuffer.isEmpty()) return@Runnable
            inferenceExecutor.execute {
                val result = runCatching {
                    medium.rerank(contextSnapshot, reading, candidatesSnapshot)
                }.getOrNull() ?: return@execute

                mainHandler.post {
                    if (epoch != candidateEpoch || currentReading != reading || romanBuffer.isEmpty()) return@post
                    currentCandidates = result.candidates
                    val badge = "✦5M ${result.latencyMs}ms"
                    showCandidates(result.candidates, badge) { commitCandidate(it) }
                }
            }
        }
        pendingMediumRerank = runnable
        mainHandler.postDelayed(runnable, 140L)
    }

    private fun cancelPendingRerank(incrementEpoch: Boolean = true) {
        pendingMediumRerank?.let(mainHandler::removeCallbacks)
        pendingMediumRerank = null
        if (incrementEpoch) candidateEpoch++
    }

    private fun handleBackspace() {
        cancelPendingRerank()
        if (romanBuffer.isNotEmpty()) {
            romanBuffer.deleteCharAt(romanBuffer.lastIndex)
            if (romanBuffer.isEmpty()) {
                currentReading = ""
                currentCandidates = emptyList()
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
                compositionContext = ""
                postNextPredictions()
            } else {
                refreshCompositionAndCandidates()
            }
            return
        }
        currentInputConnection?.deleteSurroundingText(1, 0)
        postNextPredictions()
    }

    private fun handleSpace() {
        cancelPendingRerank()
        if (romanBuffer.isNotEmpty()) {
            val reading = RomajiConverter.convert(romanBuffer.toString())
            val candidate = if (currentReading == reading) currentCandidates.firstOrNull() else null
            commitCandidate(candidate ?: CandidateGenerator.candidates(reading).firstOrNull() ?: reading)
        } else {
            currentInputConnection?.commitText(" ", 1)
            postNextPredictions()
        }
    }

    private fun handleEnter() {
        cancelPendingRerank()
        if (romanBuffer.isNotEmpty()) {
            val reading = RomajiConverter.convert(romanBuffer.toString())
            val candidate = if (currentReading == reading) currentCandidates.firstOrNull() else null
            commitCandidate(candidate ?: CandidateGenerator.candidates(reading).firstOrNull() ?: reading)
            return
        }
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        postNextPredictions()
    }

    private fun handlePunctuation(mark: String) {
        cancelPendingRerank()
        if (romanBuffer.isNotEmpty()) {
            val reading = RomajiConverter.convert(romanBuffer.toString())
            val candidate = if (currentReading == reading) currentCandidates.firstOrNull() else null
            currentInputConnection?.commitText(candidate ?: reading, 1)
            romanBuffer.clear()
            currentReading = ""
            currentCandidates = emptyList()
            compositionContext = ""
        }
        currentInputConnection?.commitText(mark, 1)
        postNextPredictions()
    }

    private fun commitCandidate(candidate: String) {
        cancelPendingRerank()
        currentInputConnection?.commitText(candidate, 1)
        romanBuffer.clear()
        currentReading = ""
        currentCandidates = emptyList()
        compositionContext = ""
        postNextPredictions()
    }

    private fun toggleMode(button: Button) {
        cancelPendingRerank()
        if (romanBuffer.isNotEmpty()) {
            val reading = RomajiConverter.convert(romanBuffer.toString())
            currentInputConnection?.commitText(currentCandidates.firstOrNull() ?: reading, 1)
            romanBuffer.clear()
            currentReading = ""
            currentCandidates = emptyList()
            compositionContext = ""
        }
        japaneseMode = !japaneseMode
        button.text = if (japaneseMode) "英数" else "かな"
        postNextPredictions()
    }

    private fun postNextPredictions() {
        if (!aiActive() || !japaneseMode || romanBuffer.isNotEmpty()) {
            if (romanBuffer.isEmpty()) showCandidates(emptyList(), null) { }
            return
        }
        val predictions = model?.predictNext(textBeforeCursor(), 5).orEmpty().map { it.text }
        currentCandidates = predictions
        showCandidates(predictions, if (predictions.isNotEmpty()) "✦" else null) { prediction ->
            currentInputConnection?.commitText(prediction, 1)
            postNextPredictions()
        }
    }

    private fun showCandidates(
        candidates: List<String>,
        aiBadge: String?,
        onClick: (String) -> Unit
    ) {
        val row = candidateRow ?: return
        row.removeAllViews()
        candidates.forEachIndexed { index, candidate ->
            val aiFirst = index == 0 && aiBadge != null
            row.addView(TextView(this).apply {
                text = if (aiFirst) "$candidate  $aiBadge" else candidate
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(25, 25, 25))
                setPadding(15.dp(), 0, 15.dp(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 17.dp().toFloat()
                    setColor(if (aiFirst) Color.rgb(230, 235, 255) else Color.WHITE)
                    setStroke(1.dp(), Color.rgb(220, 220, 220))
                }
                setOnClickListener { onClick(candidate) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 38.dp()).apply {
                marginStart = 3.dp()
                marginEnd = 3.dp()
            })
        }
    }

    private fun textBeforeCursor(): String = currentInputConnection
        ?.getTextBeforeCursor(120, 0)
        ?.toString()
        .orEmpty()

    private fun aiActive(): Boolean = aiEnabledByUser && !secureField && model != null

    private fun isPasswordField(info: EditorInfo): Boolean {
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        val clazz = type and InputType.TYPE_MASK_CLASS
        return when (clazz) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
