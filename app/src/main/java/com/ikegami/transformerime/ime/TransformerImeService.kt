package com.ikegami.transformerime.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.ikegami.transformerime.model.MediumMoETransformer
import com.ikegami.transformerime.model.ModelRepository
import com.ikegami.transformerime.model.TinyTransformerModel
import java.util.concurrent.Executors
import kotlin.math.abs

class TransformerImeService : InputMethodService() {
    private val compositionBuffer = StringBuilder()
    private var compositionContext = ""
    private var candidateRow: LinearLayout? = null
    private var keyboardContainer: LinearLayout? = null
    private var japaneseMode = true
    private var englishShift = false
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
            // Dictionary copy/open and model decoding both happen away from the UI thread.
            CandidateGenerator.initialize(this)
            mediumModel = runCatching { MediumMoETransformer.load(this) }.getOrNull()
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
        compositionBuffer.clear()
        compositionContext = ""
        currentReading = ""
        currentCandidates = emptyList()
        secureField = attribute?.let(::isPasswordField) ?: false
        aiEnabledByUser = getSharedPreferences("transformer_ime", Context.MODE_PRIVATE)
            .getBoolean("ai_enabled", true)
    }

    override fun onFinishInput() {
        cancelPendingRerank()
        compositionBuffer.clear()
        compositionContext = ""
        currentReading = ""
        currentCandidates = emptyList()
        candidateRow?.removeAllViews()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val side = 4.dp()
        val top = 4.dp()
        val minimumBottomSafe = 44.dp()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, top, side, minimumBottomSafe)
            setBackgroundColor(Color.rgb(245, 245, 245))
            setOnApplyWindowInsetsListener { view, insets ->
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                val gestures = insets.getInsets(WindowInsets.Type.systemGestures())
                val mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
                val tappable = insets.getInsets(WindowInsets.Type.tappableElement())
                val reportedBottom = maxOf(nav.bottom, gestures.bottom, mandatory.bottom, tappable.bottom)
                view.setPadding(side, top, side, maxOf(minimumBottomSafe, reportedBottom + 8.dp()))
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
        scroll.addView(
            candidateRow,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 50.dp())
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp())
        )

        keyboardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            keyboardContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        renderKeyboard()

        root.requestApplyInsets()
        postNextPredictions()
        return root
    }

    private fun renderKeyboard() {
        val container = keyboardContainer ?: return
        container.removeAllViews()
        if (japaneseMode) buildJapaneseFlickKeyboard(container) else buildEnglishQwertyKeyboard(container)
    }

    private fun buildJapaneseFlickKeyboard(root: LinearLayout) {
        addFlickFunctionRow(root, listOf("あ", "か", "さ"), "⌫") { handleBackspace() }
        addFlickFunctionRow(root, listOf("た", "な", "は"), "゛゜小") { handleKanaModifier() }
        addFlickFunctionRow(root, listOf("ま", "や", "ら"), "⏎") { handleEnter() }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton("英数", 1f) { toggleMode(false) })
        bottom.addView(flickButton("わ", 1f))
        bottom.addView(flickButton("、。", 1f))
        bottom.addView(keyButton("変換/空白", 1f) { handleSpace() })
        root.addView(bottom, flickRowParams())
    }

    private fun addFlickFunctionRow(
        root: LinearLayout,
        labels: List<String>,
        functionLabel: String,
        function: () -> Unit
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEach { row.addView(flickButton(it, 1f)) }
        row.addView(keyButton(functionLabel, 1f) { function() })
        root.addView(row, flickRowParams())
    }

    private fun flickButton(label: String, weight: Float): Button {
        val set = requireNotNull(FlickKana.keys[label])
        var downX = 0f
        var downY = 0f
        val threshold = 22.dp().toFloat()

        return styledButton(label).apply {
            textSize = 21f
            contentDescription = "$label 左${set.left} 上${set.up} 右${set.right} 下${set.down}"
            setOnClickListener { }
            setOnTouchListener { view, event ->
                fun direction(x: Float, y: Float): FlickDirection {
                    val dx = x - downX
                    val dy = y - downY
                    if (abs(dx) < threshold && abs(dy) < threshold) return FlickDirection.CENTER
                    return if (abs(dx) >= abs(dy)) {
                        if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
                    } else {
                        if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
                    }
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        isPressed = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        text = set.value(direction(event.x, event.y))
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val output = set.value(direction(event.x, event.y))
                        text = label
                        isPressed = false
                        view.performClick()
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        handleFlickOutput(output)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        text = label
                        isPressed = false
                        true
                    }
                    else -> true
                }
            }
        }.also {
            it.layoutParams = weightedKeyParams(weight)
        }
    }

    private fun handleFlickOutput(output: String) {
        if (output in setOf("、", "。", "？", "！", "…", "「", "」", "〜")) {
            handlePunctuation(output)
        } else {
            handleKana(output)
        }
    }

    private fun handleKana(kana: String) {
        if (!japaneseMode) return
        cancelPendingRerank()
        if (compositionBuffer.isEmpty()) compositionContext = textBeforeCursor()
        compositionBuffer.append(kana)
        refreshCompositionAndCandidates()
    }

    private fun handleKanaModifier() {
        if (compositionBuffer.isEmpty()) return
        cancelPendingRerank()
        val modified = FlickKana.modifyLast(compositionBuffer.toString())
        if (modified != compositionBuffer.toString()) {
            compositionBuffer.clear()
            compositionBuffer.append(modified)
            refreshCompositionAndCandidates()
        }
    }

    private fun buildEnglishQwertyKeyboard(root: LinearLayout) {
        addEnglishLetterRow(root, listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
        addEnglishLetterRow(root, listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), 13)

        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        third.addView(keyButton(if (englishShift) "⇧●" else "⇧", 1.25f) {
            englishShift = !englishShift
            renderKeyboard()
        })
        listOf("z", "x", "c", "v", "b", "n", "m").forEach { letter ->
            third.addView(keyButton(displayEnglishLetter(letter), 1f) { handleEnglishLetter(letter) })
        }
        third.addView(keyButton("⌫", 1.25f) { handleBackspace() })
        root.addView(third, qwertyRowParams())

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton("かな", 1.2f) { toggleMode(true) })
        bottom.addView(keyButton(",", 0.8f) { commitEnglishText(",") })
        bottom.addView(keyButton("space", 3.3f) { commitEnglishText(" ") })
        bottom.addView(keyButton(".", 0.8f) { commitEnglishText(".") })
        bottom.addView(keyButton("⏎", 1.2f) { handleEnter() })
        root.addView(bottom, qwertyRowParams())
    }

    private fun addEnglishLetterRow(root: LinearLayout, letters: List<String>, horizontalPadding: Int = 0) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalPadding.dp(), 0, horizontalPadding.dp(), 0)
        }
        letters.forEach { letter ->
            row.addView(keyButton(displayEnglishLetter(letter), 1f) { handleEnglishLetter(letter) })
        }
        root.addView(row, qwertyRowParams())
    }

    private fun displayEnglishLetter(letter: String): String = if (englishShift) letter.uppercase() else letter

    private fun handleEnglishLetter(letter: String) {
        val value = if (englishShift) letter.uppercase() else letter
        currentInputConnection?.commitText(value, 1)
        if (englishShift) {
            englishShift = false
            renderKeyboard()
        }
        showCandidates(emptyList(), null) { }
    }

    private fun commitEnglishText(text: String) {
        currentInputConnection?.commitText(text, 1)
        showCandidates(emptyList(), null) { }
    }

    private fun flickRowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        61.dp()
    ).apply { topMargin = 3.dp() }

    private fun qwertyRowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        55.dp()
    ).apply { topMargin = 3.dp() }

    private fun keyButton(label: String, weight: Float, action: (View) -> Unit): Button =
        styledButton(label).apply {
            textSize = if (label.length > 4) 12f else 18f
            setOnClickListener(action)
        }.also {
            it.layoutParams = weightedKeyParams(weight)
        }

    private fun styledButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(1.dp(), 0, 1.dp(), 0)
        background = GradientDrawable().apply {
            cornerRadius = 8.dp().toFloat()
            setColor(Color.WHITE)
            setStroke(1.dp(), Color.rgb(220, 220, 220))
        }
    }

    private fun weightedKeyParams(weight: Float) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        weight
    ).apply {
        marginStart = 2.dp()
        marginEnd = 2.dp()
    }

    private fun refreshCompositionAndCandidates() {
        val reading = compositionBuffer.toString()
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
            if (epoch != candidateEpoch || currentReading != reading || compositionBuffer.isEmpty()) return@Runnable
            inferenceExecutor.execute {
                val result = runCatching {
                    medium.rerank(contextSnapshot, reading, candidatesSnapshot)
                }.getOrNull() ?: return@execute

                mainHandler.post {
                    if (epoch != candidateEpoch || currentReading != reading || compositionBuffer.isEmpty()) return@post
                    currentCandidates = result.candidates
                    val modelTag = if (medium.corpusTrained) "✦JP5M" else "✦5M"
                    val dictionaryTag = if (CandidateGenerator.extendedDictionaryReady) "·D" else ""
                    showCandidates(result.candidates, "$modelTag$dictionaryTag ${result.latencyMs}ms") { commitCandidate(it) }
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
        if (compositionBuffer.isNotEmpty()) {
            compositionBuffer.deleteCharAt(compositionBuffer.lastIndex)
            if (compositionBuffer.isEmpty()) {
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
        if (japaneseMode) postNextPredictions()
    }

    private fun handleSpace() {
        cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) {
            commitCandidate(currentCandidates.firstOrNull() ?: currentReading)
        } else {
            currentInputConnection?.commitText(" ", 1)
            if (japaneseMode) postNextPredictions()
        }
    }

    private fun handleEnter() {
        cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) {
            commitCandidate(currentCandidates.firstOrNull() ?: currentReading)
            return
        }
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        if (japaneseMode) postNextPredictions()
    }

    private fun handlePunctuation(mark: String) {
        cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) {
            val candidate = currentCandidates.firstOrNull() ?: currentReading
            currentInputConnection?.commitText(candidate, 1)
            clearCompositionState()
        }
        currentInputConnection?.commitText(mark, 1)
        postNextPredictions()
    }

    private fun commitCandidate(candidate: String) {
        cancelPendingRerank()
        currentInputConnection?.commitText(candidate, 1)
        clearCompositionState()
        postNextPredictions()
    }

    private fun clearCompositionState() {
        compositionBuffer.clear()
        currentReading = ""
        currentCandidates = emptyList()
        compositionContext = ""
    }

    private fun toggleMode(toJapanese: Boolean) {
        cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) {
            currentInputConnection?.commitText(currentCandidates.firstOrNull() ?: currentReading, 1)
            clearCompositionState()
        }
        japaneseMode = toJapanese
        englishShift = false
        renderKeyboard()
        postNextPredictions()
    }

    private fun postNextPredictions() {
        if (!aiActive() || !japaneseMode || compositionBuffer.isNotEmpty()) {
            if (compositionBuffer.isEmpty()) showCandidates(emptyList(), null) { }
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
