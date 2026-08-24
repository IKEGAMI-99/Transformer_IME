package com.ikegami.transformerime.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import com.ikegami.transformerime.conversion.NextCandidateGenerator
import com.ikegami.transformerime.learning.UserLearningStore
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
    private var learningStore: UserLearningStore? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var mediumModel: MediumMoETransformer? = null
    private var pendingMediumRerank: Runnable? = null
    private var pendingNextPrediction: Runnable? = null
    private var candidateEpoch = 0
    private var predictionEpoch = 0
    private var currentReading = ""
    private var currentCandidates: List<String> = emptyList()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        model = ModelRepository.get(this)
        learningStore = UserLearningStore(this)
        inferenceExecutor.execute {
            CandidateGenerator.initialize(this)
            mediumModel = runCatching { MediumMoETransformer.load(this) }.getOrNull()
        }
    }

    override fun onDestroy() {
        cancelPendingRerank()
        cancelPendingNextPrediction()
        runCatching { mediumModel?.close() }
        runCatching { learningStore?.close() }
        inferenceExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cancelPendingRerank()
        cancelPendingNextPrediction()
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
        cancelPendingNextPrediction()
        compositionBuffer.clear()
        compositionContext = ""
        currentReading = ""
        currentCandidates = emptyList()
        candidateRow?.removeAllViews()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val side = 0
        val top = 0
        val minimumBottomSafe = 44.dp()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, top, side, minimumBottomSafe)
            setBackgroundColor(BLACK)
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

        val candidateHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CANDIDATE_BG)
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(CANDIDATE_BG)
        }
        candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 0, 6.dp(), 0)
            setBackgroundColor(CANDIDATE_BG)
        }
        scroll.addView(candidateRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 50.dp()))
        candidateHost.addView(scroll, LinearLayout.LayoutParams(0, 50.dp(), 1f))
        root.addView(candidateHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp()))

        keyboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BLACK)
        }
        root.addView(keyboardContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        addJapaneseRow(root, "↶", listOf("あ", "か", "さ"), "⌫", ::handleUndo, ::handleBackspace)
        addJapaneseRow(root, "◀", listOf("た", "な", "は"), "▶", { handleCursor(-1) }, { handleCursor(1) })
        addJapaneseRow(root, "☺記", listOf("ま", "や", "ら"), "変換", ::showSymbolCandidates, ::handleConversionKey)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BLACK)
        }
        bottom.addView(functionButton("あa1", pill = true) { toggleMode(false) }, japaneseSideParams())
        bottom.addView(functionButton("゛゜\n大小") { handleKanaModifier() }, japaneseCenterParams())
        bottom.addView(flickButton("わ"), japaneseCenterParams())
        bottom.addView(flickButton("、。"), japaneseCenterParams())
        bottom.addView(functionButton("↵", accent = true) { handleEnter() }, japaneseSideParams())
        root.addView(bottom, japaneseRowParams())
    }

    private fun addJapaneseRow(
        root: LinearLayout,
        leftLabel: String,
        kanaLabels: List<String>,
        rightLabel: String,
        leftAction: () -> Unit,
        rightAction: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BLACK)
        }
        row.addView(functionButton(leftLabel) { leftAction() }, japaneseSideParams())
        kanaLabels.forEach { label -> row.addView(flickButton(label), japaneseCenterParams()) }
        row.addView(functionButton(rightLabel) { rightAction() }, japaneseSideParams())
        root.addView(row, japaneseRowParams())
    }

    private fun flickButton(label: String): Button {
        val set = requireNotNull(FlickKana.keys[label])
        var downX = 0f
        var downY = 0f
        val threshold = 22.dp().toFloat()

        return darkButton(label, large = true).apply {
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
                        cancelPendingNextPrediction()
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
        }
    }

    private fun functionButton(label: String, pill: Boolean = false, accent: Boolean = false, action: () -> Unit): Button =
        darkButton(label, large = false, pill = pill, accent = accent).apply {
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    private fun darkButton(label: String, large: Boolean, pill: Boolean = false, accent: Boolean = false): Button =
        Button(this).apply {
            text = label
            textSize = if (large) 26f else if (label.length > 3) 14f else 19f
            setTextColor(Color.rgb(238, 238, 238))
            gravity = Gravity.CENTER
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = keyStateDrawable(pill, accent)
        }

    private fun keyStateDrawable(pill: Boolean, accent: Boolean): StateListDrawable {
        fun shape(color: Int): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = if (pill || accent) 26.dp().toFloat() else 0f
            if (!pill && !accent) setStroke(1, GRID_LINE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (accent) ACCENT_PRESSED else PRESSED))
            addState(intArrayOf(), shape(if (accent) ACCENT else if (pill) MODE_PILL else BLACK))
        }
    }

    private fun handleFlickOutput(output: String) {
        if (output in setOf("、", "。", "？", "！", "…", "「", "」", "〜")) handlePunctuation(output)
        else handleKana(output)
    }

    private fun handleKana(kana: String) {
        if (!japaneseMode) return
        cancelPendingNextPrediction()
        cancelPendingRerank()
        if (compositionBuffer.isEmpty()) compositionContext = textBeforeCursor()
        compositionBuffer.append(kana)
        refreshCompositionAndCandidates()
    }

    private fun handleKanaModifier() {
        if (compositionBuffer.isEmpty()) return
        cancelPendingNextPrediction()
        cancelPendingRerank()
        val modified = FlickKana.modifyLast(compositionBuffer.toString())
        if (modified != compositionBuffer.toString()) {
            compositionBuffer.clear()
            compositionBuffer.append(modified)
            refreshCompositionAndCandidates()
        }
    }

    private fun handleUndo() {
        cancelPendingNextPrediction()
        currentInputConnection?.performContextMenuAction(android.R.id.undo)
        postNextPredictions()
    }

    private fun handleCursor(delta: Int) {
        cancelPendingNextPrediction()
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        postNextPredictions()
    }

    private fun showSymbolCandidates() {
        cancelPendingNextPrediction()
        val symbols = listOf("。", "、", "！", "？", "…", "〜", "・", "「", "」", "（", "）", "＠", "＃", "＆")
        showCandidates(symbols, null) { symbol ->
            currentInputConnection?.commitText(symbol, 1)
            postNextPredictions()
        }
    }

    private fun handleConversionKey() {
        cancelPendingNextPrediction()
        if (compositionBuffer.isNotEmpty()) commitCandidate(currentCandidates.firstOrNull() ?: currentReading)
        else {
            currentInputConnection?.commitText(" ", 1)
            postNextPredictions()
        }
    }

    private fun buildEnglishQwertyKeyboard(root: LinearLayout) {
        addEnglishLetterRow(root, listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
        addEnglishLetterRow(root, listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), 13)
        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BLACK) }
        third.addView(qwertyButton(if (englishShift) "⇧●" else "⇧", 1.25f) {
            englishShift = !englishShift
            renderKeyboard()
        })
        listOf("z", "x", "c", "v", "b", "n", "m").forEach { letter ->
            third.addView(qwertyButton(displayEnglishLetter(letter), 1f) { handleEnglishLetter(letter) })
        }
        third.addView(qwertyButton("⌫", 1.25f) { handleBackspace() })
        root.addView(third, qwertyRowParams())

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BLACK) }
        bottom.addView(qwertyButton("かな", 1.2f) { toggleMode(true) })
        bottom.addView(qwertyButton(",", 0.8f) { commitEnglishText(",") })
        bottom.addView(qwertyButton("space", 3.3f) { commitEnglishText(" ") })
        bottom.addView(qwertyButton(".", 0.8f) { commitEnglishText(".") })
        bottom.addView(qwertyButton("↵", 1.2f, accent = true) { handleEnter() })
        root.addView(bottom, qwertyRowParams())
    }

    private fun addEnglishLetterRow(root: LinearLayout, letters: List<String>, horizontalPadding: Int = 0) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalPadding.dp(), 0, horizontalPadding.dp(), 0)
            setBackgroundColor(BLACK)
        }
        letters.forEach { letter -> row.addView(qwertyButton(displayEnglishLetter(letter), 1f) { handleEnglishLetter(letter) }) }
        root.addView(row, qwertyRowParams())
    }

    private fun qwertyButton(label: String, weight: Float, accent: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = if (label.length > 4) 12f else 18f
            setTextColor(Color.WHITE)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(1.dp(), 0, 1.dp(), 0)
            background = qwertyStateDrawable(accent)
            setOnClickListener { action() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                marginStart = 2.dp(); marginEnd = 2.dp(); topMargin = 2.dp(); bottomMargin = 2.dp()
            }
        }

    private fun qwertyStateDrawable(accent: Boolean): StateListDrawable {
        fun shape(color: Int): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 6.dp().toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (accent) ACCENT_PRESSED else Color.rgb(78, 78, 78)))
            addState(intArrayOf(), shape(if (accent) ACCENT else Color.rgb(48, 48, 48)))
        }
    }

    private fun displayEnglishLetter(letter: String): String = if (englishShift) letter.uppercase() else letter

    private fun handleEnglishLetter(letter: String) {
        cancelPendingNextPrediction()
        val value = if (englishShift) letter.uppercase() else letter
        currentInputConnection?.commitText(value, 1)
        if (englishShift) {
            englishShift = false
            renderKeyboard()
        }
        showCandidates(emptyList(), null) { }
    }

    private fun commitEnglishText(text: String) {
        cancelPendingNextPrediction()
        currentInputConnection?.commitText(text, 1)
        showCandidates(emptyList(), null) { }
    }

    private fun japaneseRowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60.dp())
    private fun japaneseSideParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.82f)
    private fun japaneseCenterParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.18f)
    private fun qwertyRowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 55.dp())

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
        val personalized = if (!secureField) learningStore?.rankConversions(reading, base) ?: base else base
        val tinyRanked = if (aiActive()) model?.rankCandidates(compositionContext, personalized) ?: personalized else personalized
        val visible = if (aiActive() && tinyRanked.isNotEmpty()) {
            forceRawReadingSecond(tinyRanked.first(), reading, tinyRanked.drop(1))
        } else personalized
        currentCandidates = visible
        showCandidates(visible, if (aiActive() && visible.isNotEmpty()) "✦" else null, aiSlots = setOf(0)) { commitCandidate(it) }
        scheduleMediumRerank(reading, tinyRanked)
    }

    private fun scheduleMediumRerank(reading: String, candidates: List<String>) {
        cancelPendingRerank(incrementEpoch = false)
        if (!aiActive() || secureField || candidates.isEmpty()) return
        val medium = mediumModel ?: return
        val epoch = ++candidateEpoch
        val contextSnapshot = compositionContext
        val candidatesSnapshot = candidates.toList()

        val runnable = Runnable {
            if (epoch != candidateEpoch || currentReading != reading || compositionBuffer.isEmpty()) return@Runnable
            inferenceExecutor.execute {
                val result = runCatching { medium.rerank(contextSnapshot, reading, candidatesSnapshot) }.getOrNull() ?: return@execute
                val rawSecond = if (result.candidates.isNotEmpty()) {
                    forceRawReadingSecond(result.candidates.first(), reading, result.candidates.drop(1))
                } else result.candidates
                mainHandler.post {
                    if (epoch != candidateEpoch || currentReading != reading || compositionBuffer.isEmpty()) return@post
                    currentCandidates = rawSecond
                    val modelTag = if (medium.corpusTrained) "✦Z95×10" else "✦Tiny"
                    val dictionaryTag = if (CandidateGenerator.extendedDictionaryReady) "·D" else ""
                    showCandidates(
                        rawSecond,
                        "$modelTag$dictionaryTag ${result.latencyMs}ms",
                        aiSlots = setOf(0)
                    ) { commitCandidate(it) }
                }
            }
        }
        pendingMediumRerank = runnable
        mainHandler.postDelayed(runnable, 80L)
    }

    private fun forceRawReadingSecond(aiCandidate: String, reading: String, rest: List<String>): List<String> = buildList {
        add(aiCandidate)
        add(reading)
        rest.forEach { candidate ->
            if (candidate != aiCandidate && candidate != reading) add(candidate)
        }
    }

    private fun cancelPendingRerank(incrementEpoch: Boolean = true) {
        pendingMediumRerank?.let(mainHandler::removeCallbacks)
        pendingMediumRerank = null
        if (incrementEpoch) candidateEpoch++
    }

    private fun cancelPendingNextPrediction(incrementEpoch: Boolean = true) {
        pendingNextPrediction?.let(mainHandler::removeCallbacks)
        pendingNextPrediction = null
        if (incrementEpoch) predictionEpoch++
    }

    private fun handleBackspace() {
        cancelPendingNextPrediction()
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
            } else refreshCompositionAndCandidates()
            return
        }
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (japaneseMode) postNextPredictions()
    }

    private fun handleEnter() {
        cancelPendingNextPrediction()
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
        cancelPendingNextPrediction()
        cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) {
            val candidate = currentCandidates.firstOrNull() ?: currentReading
            if (!secureField) learningStore?.recordConversion(currentReading, candidate)
            currentInputConnection?.commitText(candidate, 1)
            clearCompositionState()
        }
        currentInputConnection?.commitText(mark, 1)
        postNextPredictions()
    }

    private fun commitCandidate(candidate: String) {
        cancelPendingNextPrediction()
        cancelPendingRerank()
        val readingSnapshot = currentReading
        if (!secureField && readingSnapshot.isNotBlank()) learningStore?.recordConversion(readingSnapshot, candidate)
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
        cancelPendingNextPrediction()
        cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) {
            val candidate = currentCandidates.firstOrNull() ?: currentReading
            if (!secureField) learningStore?.recordConversion(currentReading, candidate)
            currentInputConnection?.commitText(candidate, 1)
            clearCompositionState()
        }
        japaneseMode = toJapanese
        englishShift = false
        renderKeyboard()
        if (japaneseMode) postNextPredictions() else showCandidates(emptyList(), null) { }
    }

    private fun postNextPredictions() {
        cancelPendingNextPrediction(incrementEpoch = false)
        if (!aiActive() || !japaneseMode || compositionBuffer.isNotEmpty()) {
            if (compositionBuffer.isEmpty()) showCandidates(emptyList(), null) { }
            return
        }

        val context = textBeforeCursor()
        if (context.isBlank()) {
            showCandidates(emptyList(), null) { }
            return
        }

        val tiny = model?.predictNext(context, 8).orEmpty().map { it.text }
        val rawPool = NextCandidateGenerator.candidates(context, tiny)
        val pool = if (!secureField) learningStore?.rankNext(context, rawPool) ?: rawPool else rawPool
        if (pool.isEmpty()) {
            showCandidates(emptyList(), null) { }
            return
        }

        currentCandidates = pool
        showCandidates(pool.take(8), "✦次", aiSlots = setOf(0)) { commitPrediction(it) }
        scheduleMediumNextPrediction(context, pool)
    }

    private fun scheduleMediumNextPrediction(context: String, candidates: List<String>) {
        val medium = mediumModel ?: return
        if (!aiActive() || candidates.isEmpty()) return
        val epoch = ++predictionEpoch
        val contextTail = context.takeLast(180)
        val pool = candidates.toList()

        val runnable = Runnable {
            if (epoch != predictionEpoch || compositionBuffer.isNotEmpty() || !japaneseMode) return@Runnable
            inferenceExecutor.execute {
                val result = runCatching { medium.rerank(contextTail, "", pool) }.getOrNull() ?: return@execute
                val personalized = if (!secureField) {
                    learningStore?.rankNext(contextTail, result.candidates) ?: result.candidates
                } else result.candidates
                mainHandler.post {
                    if (epoch != predictionEpoch || compositionBuffer.isNotEmpty() || !japaneseMode) return@post
                    if (textBeforeCursor().takeLast(180) != contextTail) return@post
                    currentCandidates = personalized
                    val modelTag = if (medium.corpusTrained) "✦次Z95×10" else "✦次Tiny"
                    showCandidates(
                        personalized.take(10),
                        "$modelTag ${result.latencyMs}ms",
                        aiSlots = setOf(0)
                    ) { commitPrediction(it) }
                }
            }
        }
        pendingNextPrediction = runnable
        mainHandler.postDelayed(runnable, 60L)
    }

    private fun commitPrediction(prediction: String) {
        cancelPendingNextPrediction()
        val context = textBeforeCursor()
        if (!secureField && context.isNotBlank()) learningStore?.recordNext(context, prediction)
        currentInputConnection?.commitText(prediction, 1)
        postNextPredictions()
    }

    private fun showCandidates(
        candidates: List<String>,
        aiBadge: String?,
        aiSlots: Set<Int> = if (aiBadge != null) setOf(0) else emptySet(),
        onClick: (String) -> Unit
    ) {
        val row = candidateRow ?: return
        row.removeAllViews()
        val orderedAiSlots = aiSlots.filter { it in candidates.indices }.sorted()
        candidates.forEachIndexed { index, candidate ->
            val aiRank = orderedAiSlots.indexOf(index)
            val aiHighlighted = aiBadge != null && aiRank >= 0
            val label = when {
                !aiHighlighted -> candidate
                orderedAiSlots.size <= 1 -> "$candidate  $aiBadge"
                aiRank == 0 -> "$candidate  ✦AI1  $aiBadge"
                else -> "$candidate  ✦AI${aiRank + 1}"
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(235, 235, 235))
                setPadding(16.dp(), 0, 16.dp(), 0)
                setBackgroundColor(if (aiHighlighted) Color.rgb(56, 56, 56) else Color.TRANSPARENT)
                setOnClickListener { onClick(candidate) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 42.dp()))

            if (index != candidates.lastIndex) {
                row.addView(
                    View(this).apply { setBackgroundColor(Color.rgb(82, 82, 82)) },
                    LinearLayout.LayoutParams(1.dp(), 24.dp()).apply { gravity = Gravity.CENTER_VERTICAL }
                )
            }
        }
    }

    private fun textBeforeCursor(): String = currentInputConnection
        ?.getTextBeforeCursor(240, 0)
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

    companion object {
        private val BLACK = Color.rgb(0, 0, 0)
        private val CANDIDATE_BG = Color.rgb(48, 48, 48)
        private val GRID_LINE = Color.rgb(31, 31, 31)
        private val PRESSED = Color.rgb(70, 70, 70)
        private val MODE_PILL = Color.rgb(55, 55, 55)
        private val ACCENT = Color.rgb(126, 203, 196)
        private val ACCENT_PRESSED = Color.rgb(96, 173, 166)
    }
}
