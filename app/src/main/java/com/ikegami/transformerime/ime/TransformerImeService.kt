package com.ikegami.transformerime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ikegami.transformerime.audio.AudioPulseService
import com.ikegami.transformerime.conversion.CandidateGenerator
import com.ikegami.transformerime.conversion.EnglishPredictor
import com.ikegami.transformerime.conversion.NextCandidateGenerator
import com.ikegami.transformerime.learning.UserLearningStore
import com.ikegami.transformerime.model.MediumMoETransformer
import com.ikegami.transformerime.model.ModelRepository
import com.ikegami.transformerime.model.TinyTransformerModel
import java.util.concurrent.Executors
import kotlin.math.abs

class TransformerImeService : InputMethodService() {
    private val compositionBuffer = StringBuilder()
    private val englishBuffer = StringBuilder()
    private var compositionContext = ""
    private var englishContext = ""
    private var candidateRow: LinearLayout? = null
    private var keyboardContainer: LinearLayout? = null
    private var pulseBackground: AudioPulseBackgroundView? = null
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
    private var deleteRepeat: Runnable? = null
    private var candidateEpoch = 0
    private var predictionEpoch = 0
    private var currentReading = ""
    private var currentCandidates: List<String> = emptyList()

    private val pulseRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(AudioPulseService.PREFS, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(AudioPulseService.KEY_ENABLED, false)
            val level = if (enabled) prefs.getFloat(AudioPulseService.KEY_LEVEL, 0f) else 0f
            pulseBackground?.setPulse(level, enabled)
            if (pulseBackground != null) mainHandler.postDelayed(this, 33L)
        }
    }

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
        stopDeleteRepeat()
        mainHandler.removeCallbacks(pulseRunnable)
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
        englishBuffer.clear()
        compositionContext = ""
        englishContext = ""
        currentReading = ""
        currentCandidates = emptyList()
        secureField = attribute?.let(::isPasswordField) ?: false
        aiEnabledByUser = getSharedPreferences("transformer_ime", Context.MODE_PRIVATE)
            .getBoolean("ai_enabled", true)
    }

    override fun onFinishInput() {
        cancelPendingRerank()
        cancelPendingNextPrediction()
        stopDeleteRepeat()
        compositionBuffer.clear()
        englishBuffer.clear()
        compositionContext = ""
        englishContext = ""
        currentReading = ""
        currentCandidates = emptyList()
        candidateRow?.removeAllViews()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val minimumBottomSafe = 44.dp()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val candidateHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(245, 45, 45, 45))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
        }
        candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 0, 6.dp(), 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        scroll.addView(candidateRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 50.dp()))
        candidateHost.addView(scroll, LinearLayout.LayoutParams(0, 50.dp(), 1f))
        content.addView(candidateHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp()))

        val keyboardHost = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        pulseBackground = AudioPulseBackgroundView(this)
        keyboardHost.addView(
            pulseBackground,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                320.dp(),
                Gravity.BOTTOM
            )
        )

        keyboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, minimumBottomSafe)
            setBackgroundColor(Color.TRANSPARENT)
            setOnApplyWindowInsetsListener { view, insets ->
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                val gestures = insets.getInsets(WindowInsets.Type.systemGestures())
                val mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
                val tappable = insets.getInsets(WindowInsets.Type.tappableElement())
                val bottom = maxOf(nav.bottom, gestures.bottom, mandatory.bottom, tappable.bottom)
                view.setPadding(0, 0, 0, maxOf(minimumBottomSafe, bottom + 8.dp()))
                insets
            }
        }
        keyboardHost.addView(
            keyboardContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
        content.addView(
            keyboardHost,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        renderKeyboard()
        keyboardContainer?.requestApplyInsets()
        mainHandler.removeCallbacks(pulseRunnable)
        mainHandler.post(pulseRunnable)
        if (japaneseMode) postNextPredictions() else postEnglishNextPredictions()
        return root
    }

    private fun renderKeyboard() {
        val container = keyboardContainer ?: return
        container.removeAllViews()
        if (japaneseMode) buildJapaneseFlickKeyboard(container) else buildEnglishQwertyKeyboard(container)
    }

    // ------------------------------------------------------------
    // Japanese keyboard / emoji / numeric panels
    // ------------------------------------------------------------

    private fun buildJapaneseFlickKeyboard(root: LinearLayout) {
        addJapaneseRow(root, "↶", listOf("あ", "か", "さ"), deleteRepeatButton(), ::handleUndo)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.TRANSPARENT) }
        row2.addView(functionButton("😊") { renderEmojiPanel() }, japaneseSideParams())
        listOf("た", "な", "は").forEach { row2.addView(flickButton(it), japaneseCenterParams()) }
        row2.addView(numberMenuButton(), japaneseSideParams())
        root.addView(row2, japaneseRowParams())

        addJapaneseRow(root, "記号", listOf("ま", "や", "ら"), functionButton("変換") { handleConversionKey() }, ::showSymbolCandidates)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.TRANSPARENT) }
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
        rightView: View,
        leftAction: () -> Unit
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.TRANSPARENT) }
        row.addView(functionButton(leftLabel) { leftAction() }, japaneseSideParams())
        kanaLabels.forEach { row.addView(flickButton(it), japaneseCenterParams()) }
        row.addView(rightView, japaneseSideParams())
        root.addView(row, japaneseRowParams())
    }

    private fun renderEmojiPanel() {
        val root = keyboardContainer ?: return
        root.removeAllViews()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(functionButton("← かな") { renderKeyboard() }, LinearLayout.LayoutParams(0, 48.dp(), 1f))
        header.addView(TextView(this).apply {
            text = "絵文字"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, 48.dp(), 2f))
        header.addView(deleteRepeatButton(), LinearLayout.LayoutParams(0, 48.dp(), 1f))
        root.addView(header)

        val recent = emojiRecents()
        val all = (recent + EMOJIS).distinct()
        val grid = GridLayout(this).apply { columnCount = 7; setPadding(6.dp(), 4.dp(), 6.dp(), 8.dp()) }
        all.forEach { emoji ->
            grid.addView(TextView(this).apply {
                text = emoji
                textSize = 28f
                gravity = Gravity.CENTER
                setOnClickListener {
                    currentInputConnection?.commitText(emoji, 1)
                    rememberEmoji(emoji)
                    postNextPredictions()
                }
            }, GridLayout.LayoutParams().apply { width = 50.dp(); height = 48.dp() })
        }
        root.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 192.dp()))
    }

    /** Standard keypad layer. v0.10.1 intentionally removes the radial/fan popup. */
    private fun renderNumberPanel() {
        val root = keyboardContainer ?: return
        root.removeAllViews()
        val rows = listOf(
            listOf("1", "2", "3", "⌫"),
            listOf("4", "5", "6", "-"),
            listOf("7", "8", "9", "/"),
            listOf("かな", "0", ".", "↵")
        )
        rows.forEach { labels ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4.dp(), 2.dp(), 4.dp(), 2.dp())
                setBackgroundColor(Color.TRANSPARENT)
            }
            labels.forEach { label ->
                val key: View = when (label) {
                    "⌫" -> deleteRepeatButton()
                    "かな" -> numberPadButton(label) { renderKeyboard() }
                    "↵" -> numberPadButton(label, accent = true) { handleEnter() }
                    else -> numberPadButton(label) { commitDirect(label) }
                }
                row.addView(key, LinearLayout.LayoutParams(0, 62.dp(), 1f).apply {
                    marginStart = 3.dp(); marginEnd = 3.dp(); topMargin = 2.dp(); bottomMargin = 2.dp()
                })
            }
            root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 66.dp()))
        }
    }

    private fun numberMenuButton(): Button = functionButton("123") { renderNumberPanel() }

    private fun numberPadButton(label: String, accent: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (label == "かな") 16f else 24f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            setColor(if (accent) ACCENT else Color.argb(18, 0, 0, 0))
            cornerRadius = 12.dp().toFloat()
            setStroke(1.dp(), Color.argb(110, 78, 78, 90))
        }
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun emojiRecents(): List<String> = getSharedPreferences("transformer_ime", Context.MODE_PRIVATE)
        .getString("emoji_recent", "").orEmpty().split('\u0001').filter { it.isNotBlank() }

    private fun rememberEmoji(emoji: String) {
        val recent = (listOf(emoji) + emojiRecents()).distinct().take(18)
        getSharedPreferences("transformer_ime", Context.MODE_PRIVATE).edit()
            .putString("emoji_recent", recent.joinToString("\u0001")).apply()
    }

    // ------------------------------------------------------------
    // Flick + key helpers
    // ------------------------------------------------------------

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
                    } else if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { cancelPendingNextPrediction(); downX = event.x; downY = event.y; isPressed = true; true }
                    MotionEvent.ACTION_MOVE -> { text = set.value(direction(event.x, event.y)); true }
                    MotionEvent.ACTION_UP -> {
                        val output = set.value(direction(event.x, event.y))
                        text = label; isPressed = false; view.performClick()
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        handleFlickOutput(output); true
                    }
                    MotionEvent.ACTION_CANCEL -> { text = label; isPressed = false; true }
                    else -> true
                }
            }
        }
    }

    private fun functionButton(label: String, pill: Boolean = false, accent: Boolean = false, action: () -> Unit): Button =
        darkButton(label, false, pill, accent).apply {
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action() }
        }

    private fun deleteRepeatButton(): Button = darkButton("⌫", false).apply {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handleBackspace()
                    startDeleteRepeat()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopDeleteRepeat(); true }
                else -> true
            }
        }
    }

    private fun startDeleteRepeat() {
        stopDeleteRepeat()
        var repeats = 0
        val runnable = object : Runnable {
            override fun run() {
                handleBackspace()
                repeats++
                mainHandler.postDelayed(this, if (repeats > 12) 32L else 58L)
            }
        }
        deleteRepeat = runnable
        mainHandler.postDelayed(runnable, 360L)
    }

    private fun stopDeleteRepeat() {
        deleteRepeat?.let(mainHandler::removeCallbacks)
        deleteRepeat = null
    }

    private fun darkButton(label: String, large: Boolean, pill: Boolean = false, accent: Boolean = false): Button = Button(this).apply {
        text = label
        textSize = if (large) 26f else if (label.length > 3) 14f else 19f
        setTextColor(Color.rgb(238, 238, 238))
        gravity = Gravity.CENTER
        isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = keyStateDrawable(pill, accent)
    }

    private fun keyStateDrawable(pill: Boolean, accent: Boolean): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = if (pill || accent) 26.dp().toFloat() else 0f
            if (!pill && !accent) setStroke(1, GRID_LINE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (accent) ACCENT_PRESSED else PRESSED))
            addState(intArrayOf(), shape(if (accent) ACCENT else if (pill) MODE_PILL else KEY_BLACK))
        }
    }

    private fun handleFlickOutput(output: String) {
        if (output in setOf("、", "。", "？", "！", "…", "「", "」", "〜")) handlePunctuation(output) else handleKana(output)
    }

    private fun handleKana(kana: String) {
        if (!japaneseMode) return
        cancelPendingNextPrediction(); cancelPendingRerank()
        if (compositionBuffer.isEmpty()) compositionContext = textBeforeCursor()
        compositionBuffer.append(kana)
        refreshCompositionAndCandidates()
    }

    private fun handleKanaModifier() {
        if (compositionBuffer.isEmpty()) return
        cancelPendingNextPrediction(); cancelPendingRerank()
        val modified = FlickKana.modifyLast(compositionBuffer.toString())
        if (modified != compositionBuffer.toString()) {
            compositionBuffer.clear(); compositionBuffer.append(modified); refreshCompositionAndCandidates()
        }
    }

    private fun handleUndo() {
        cancelPendingNextPrediction()
        currentInputConnection?.performContextMenuAction(android.R.id.undo)
        if (japaneseMode) postNextPredictions() else postEnglishNextPredictions()
    }

    private fun showSymbolCandidates() {
        cancelPendingNextPrediction()
        val symbols = listOf("。", "、", "！", "？", "…", "〜", "・", "「", "」", "（", "）", "＠", "＃", "＆")
        showCandidates(symbols, null) { commitDirect(it) }
    }

    private fun handleConversionKey() {
        cancelPendingNextPrediction()
        if (compositionBuffer.isNotEmpty()) commitCandidate(currentCandidates.firstOrNull() ?: currentReading)
        else commitDirect(" ")
    }

    // ------------------------------------------------------------
    // English QWERTY + completion / next-word prediction
    // ------------------------------------------------------------

    private fun buildEnglishQwertyKeyboard(root: LinearLayout) {
        addEnglishLetterRow(root, listOf("q","w","e","r","t","y","u","i","o","p"))
        addEnglishLetterRow(root, listOf("a","s","d","f","g","h","j","k","l"), 13)
        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.TRANSPARENT) }
        third.addView(qwertyButton(if (englishShift) "⇧●" else "⇧", 1.25f) { englishShift = !englishShift; renderKeyboard() })
        listOf("z","x","c","v","b","n","m").forEach { letter -> third.addView(qwertyButton(displayEnglishLetter(letter), 1f) { handleEnglishLetter(letter) }) }
        third.addView(qwertyDeleteButton(1.25f))
        root.addView(third, qwertyRowParams())

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.TRANSPARENT) }
        bottom.addView(qwertyButton("かな", 1.2f) { toggleMode(true) })
        bottom.addView(qwertyButton(",", 0.8f) { commitEnglishPunctuation(",") })
        bottom.addView(qwertyButton("space", 3.3f) { handleEnglishSpace() })
        bottom.addView(qwertyButton(".", 0.8f) { commitEnglishPunctuation(".") })
        bottom.addView(qwertyButton("↵", 1.2f, accent = true) { handleEnter() })
        root.addView(bottom, qwertyRowParams())
    }

    private fun addEnglishLetterRow(root: LinearLayout, letters: List<String>, horizontalPadding: Int = 0) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalPadding.dp(), 0, horizontalPadding.dp(), 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        letters.forEach { row.addView(qwertyButton(displayEnglishLetter(it), 1f) { handleEnglishLetter(it) }) }
        root.addView(row, qwertyRowParams())
    }

    private fun qwertyButton(label: String, weight: Float, accent: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = if (label.length > 4) 12f else 18f; setTextColor(Color.WHITE); isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(1.dp(), 0, 1.dp(), 0)
            background = qwertyStateDrawable(accent); setOnClickListener { action() }
        }.also { it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            marginStart = 2.dp(); marginEnd = 2.dp(); topMargin = 2.dp(); bottomMargin = 2.dp()
        } }

    private fun qwertyDeleteButton(weight: Float): Button = qwertyButton("⌫", weight) {}.apply {
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { handleBackspace(); startDeleteRepeat(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopDeleteRepeat(); true }
                else -> true
            }
        }
    }

    private fun qwertyStateDrawable(accent: Boolean): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 6.dp().toFloat() }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (accent) ACCENT_PRESSED else Color.argb(120, 78, 78, 88)))
            addState(intArrayOf(), shape(if (accent) ACCENT else Color.argb(16, 0, 0, 0)))
        }
    }

    private fun displayEnglishLetter(letter: String) = if (englishShift) letter.uppercase() else letter

    private fun handleEnglishLetter(letter: String) {
        cancelPendingNextPrediction()
        if (englishBuffer.isEmpty()) englishContext = textBeforeCursor()
        val value = if (englishShift) letter.uppercase() else letter
        englishBuffer.append(value)
        currentInputConnection?.setComposingText(englishBuffer.toString(), 1)
        englishShift = false
        showEnglishSuggestions()
    }

    private fun showEnglishSuggestions() {
        val prefix = englishBuffer.toString()
        if (prefix.isBlank()) { postEnglishNextPredictions(); return }
        val rag = if (!secureField) learningStore?.retrieveEnglish(prefix, englishContext, 8).orEmpty() else emptyList()
        val base = EnglishPredictor.suggestions(prefix, englishContext, 10)
        val pool = (rag + base).filter { it.isNotBlank() }.distinct()
        val ranked = if (!secureField) learningStore?.rankEnglish(prefix, englishContext, pool) ?: pool else pool
        currentCandidates = ranked
        showCandidates(ranked.take(10), if (rag.isNotEmpty()) "RAG" else null, aiSlots = if (rag.isNotEmpty()) setOf(0) else emptySet()) {
            commitEnglishCandidate(it)
        }
    }

    private fun commitEnglishCandidate(word: String) {
        val prefix = englishBuffer.toString()
        val context = englishContext
        if (!secureField) {
            learningStore?.recordEnglish(prefix, word, context)
            learningStore?.recordCommitted(context, word)
        }
        currentInputConnection?.commitText(word + " ", 1)
        englishBuffer.clear(); englishContext = ""; currentCandidates = emptyList()
        postEnglishNextPredictions()
    }

    private fun commitEnglishBuffer(addSpace: Boolean) {
        if (englishBuffer.isEmpty()) { if (addSpace) currentInputConnection?.commitText(" ", 1); return }
        val word = englishBuffer.toString()
        val context = englishContext
        if (!secureField) {
            learningStore?.recordEnglish(word, word, context)
            learningStore?.recordCommitted(context, word)
        }
        currentInputConnection?.commitText(word + if (addSpace) " " else "", 1)
        englishBuffer.clear(); englishContext = ""; currentCandidates = emptyList()
    }

    private fun handleEnglishSpace() {
        commitEnglishBuffer(addSpace = true)
        postEnglishNextPredictions()
    }

    private fun commitEnglishPunctuation(mark: String) {
        commitEnglishBuffer(addSpace = false)
        currentInputConnection?.commitText(mark, 1)
        postEnglishNextPredictions()
    }

    private fun postEnglishNextPredictions() {
        if (japaneseMode || englishBuffer.isNotEmpty()) return
        val context = textBeforeCursor()
        val rag = if (!secureField) learningStore?.retrieveEnglish("", context, 8).orEmpty() else emptyList()
        val base = EnglishPredictor.nextWords(context, 10)
        val pool = (rag + base).distinct()
        val ranked = if (!secureField) learningStore?.rankEnglish("", context, pool) ?: pool else pool
        currentCandidates = ranked
        showCandidates(ranked.take(10), if (rag.isNotEmpty()) "RAG" else null, aiSlots = if (rag.isNotEmpty()) setOf(0) else emptySet()) {
            if (!secureField) learningStore?.recordEnglish("", it, context)
            currentInputConnection?.commitText(it + " ", 1)
            postEnglishNextPredictions()
        }
    }

    // ------------------------------------------------------------
    // Japanese conversion + Personal RAG + Zenzai
    // ------------------------------------------------------------

    private fun refreshCompositionAndCandidates() {
        val reading = compositionBuffer.toString()
        currentReading = reading
        if (reading.isEmpty()) {
            currentCandidates = emptyList(); currentInputConnection?.finishComposingText(); postNextPredictions(); return
        }
        currentInputConnection?.setComposingText(reading, 1)
        val rag = if (!secureField) learningStore?.retrieveConversions(reading, 6).orEmpty() else emptyList()
        val base = (rag + CandidateGenerator.candidates(reading)).distinct()
        val personalized = if (!secureField) learningStore?.rankConversions(reading, base) ?: base else base
        val tinyRanked = if (aiActive()) model?.rankCandidates(compositionContext, personalized) ?: personalized else personalized
        val visible = if (aiActive() && tinyRanked.isNotEmpty()) forceRawReadingSecond(tinyRanked.first(), reading, tinyRanked.drop(1)) else personalized
        currentCandidates = visible
        showCandidates(visible, if (aiActive() && visible.isNotEmpty()) if (rag.isNotEmpty()) "✦·R" else "✦" else null, aiSlots = setOf(0)) { commitCandidate(it) }
        scheduleMediumRerank(reading, (rag + tinyRanked).distinct(), rag.isNotEmpty())
    }

    private fun scheduleMediumRerank(reading: String, candidates: List<String>, ragUsed: Boolean) {
        cancelPendingRerank(incrementEpoch = false)
        if (!aiActive() || secureField || candidates.isEmpty()) return
        val medium = mediumModel ?: return
        val epoch = ++candidateEpoch
        val contextSnapshot = compositionContext
        val snapshot = candidates.toList()
        val runnable = Runnable {
            if (epoch != candidateEpoch || currentReading != reading || compositionBuffer.isEmpty()) return@Runnable
            inferenceExecutor.execute {
                val result = runCatching { medium.rerank(contextSnapshot, reading, snapshot) }.getOrNull() ?: return@execute
                val rawSecond = if (result.candidates.isNotEmpty()) forceRawReadingSecond(result.candidates.first(), reading, result.candidates.drop(1)) else result.candidates
                mainHandler.post {
                    if (epoch != candidateEpoch || currentReading != reading || compositionBuffer.isEmpty()) return@post
                    currentCandidates = rawSecond
                    val dictionaryTag = if (CandidateGenerator.extendedDictionaryReady) "·D" else ""
                    val ragTag = if (ragUsed) "·R" else ""
                    showCandidates(rawSecond, "✦Z95×10$dictionaryTag$ragTag ${result.latencyMs}ms", aiSlots = setOf(0)) { commitCandidate(it) }
                }
            }
        }
        pendingMediumRerank = runnable
        mainHandler.postDelayed(runnable, 80L)
    }

    private fun forceRawReadingSecond(aiCandidate: String, reading: String, rest: List<String>): List<String> = buildList {
        add(aiCandidate); add(reading)
        rest.forEach { if (it != aiCandidate && it != reading) add(it) }
    }.distinct()

    private fun postNextPredictions() {
        cancelPendingNextPrediction(incrementEpoch = false)
        if (!aiActive() || !japaneseMode || compositionBuffer.isNotEmpty()) {
            if (compositionBuffer.isEmpty() && japaneseMode) showCandidates(emptyList(), null) { }
            return
        }
        val context = textBeforeCursor()
        if (context.isBlank()) { showCandidates(emptyList(), null) { }; return }
        val rag = if (!secureField) learningStore?.retrieveNext(context, 8).orEmpty() else emptyList()
        val tiny = model?.predictNext(context, 8).orEmpty().map { it.text }
        val generated = NextCandidateGenerator.candidates(context, tiny)
        val rawPool = (rag + generated).filter { it.isNotBlank() }.distinct()
        val pool = if (!secureField) learningStore?.rankNext(context, rawPool) ?: rawPool else rawPool
        if (pool.isEmpty()) { showCandidates(emptyList(), null) { }; return }
        currentCandidates = pool
        showCandidates(pool.take(10), if (rag.isNotEmpty()) "✦次·R" else "✦次", aiSlots = setOf(0)) { commitPrediction(it) }
        scheduleMediumNextPrediction(context, pool, rag.isNotEmpty())
    }

    private fun scheduleMediumNextPrediction(context: String, candidates: List<String>, ragUsed: Boolean) {
        val medium = mediumModel ?: return
        if (!aiActive() || candidates.isEmpty()) return
        val epoch = ++predictionEpoch
        val contextTail = context.takeLast(180)
        val pool = candidates.toList()
        val runnable = Runnable {
            if (epoch != predictionEpoch || compositionBuffer.isNotEmpty() || !japaneseMode) return@Runnable
            inferenceExecutor.execute {
                val result = runCatching { medium.rerank(contextTail, "", pool) }.getOrNull() ?: return@execute
                val ai = result.candidates.firstOrNull()
                val rest = result.candidates.drop(1)
                val rankedRest = if (!secureField) learningStore?.rankNext(contextTail, rest) ?: rest else rest
                val visible = (listOfNotNull(ai) + rankedRest).distinct()
                mainHandler.post {
                    if (epoch != predictionEpoch || compositionBuffer.isNotEmpty() || !japaneseMode) return@post
                    if (textBeforeCursor().takeLast(180) != contextTail) return@post
                    currentCandidates = visible
                    val ragTag = if (ragUsed) "·R" else ""
                    showCandidates(visible.take(10), "✦次Z95×10$ragTag ${result.latencyMs}ms", aiSlots = setOf(0)) { commitPrediction(it) }
                }
            }
        }
        pendingNextPrediction = runnable
        mainHandler.postDelayed(runnable, 60L)
    }

    private fun commitCandidate(candidate: String) {
        cancelPendingNextPrediction(); cancelPendingRerank()
        val reading = currentReading
        val context = compositionContext
        if (!secureField && reading.isNotBlank()) {
            learningStore?.recordConversion(reading, candidate)
            learningStore?.recordCommitted(context, candidate)
        }
        currentInputConnection?.commitText(candidate, 1)
        clearCompositionState(); postNextPredictions()
    }

    private fun commitPrediction(prediction: String) {
        cancelPendingNextPrediction()
        val context = textBeforeCursor()
        if (!secureField && context.isNotBlank()) {
            learningStore?.recordNext(context, prediction)
            learningStore?.recordCommitted(context, prediction)
        }
        currentInputConnection?.commitText(prediction, 1)
        postNextPredictions()
    }

    private fun handlePunctuation(mark: String) {
        cancelPendingNextPrediction(); cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) {
            val candidate = currentCandidates.firstOrNull() ?: currentReading
            val context = compositionContext
            if (!secureField) {
                learningStore?.recordConversion(currentReading, candidate)
                learningStore?.recordCommitted(context, candidate)
            }
            currentInputConnection?.commitText(candidate, 1); clearCompositionState()
        }
        currentInputConnection?.commitText(mark, 1); postNextPredictions()
    }

    // ------------------------------------------------------------
    // Shared editing / state
    // ------------------------------------------------------------

    private fun handleBackspace() {
        cancelPendingNextPrediction(); cancelPendingRerank()
        if (!japaneseMode && englishBuffer.isNotEmpty()) {
            englishBuffer.deleteCharAt(englishBuffer.lastIndex)
            if (englishBuffer.isEmpty()) {
                currentInputConnection?.setComposingText("", 1); currentInputConnection?.finishComposingText(); englishContext = ""; postEnglishNextPredictions()
            } else {
                currentInputConnection?.setComposingText(englishBuffer.toString(), 1); showEnglishSuggestions()
            }
            return
        }
        if (compositionBuffer.isNotEmpty()) {
            compositionBuffer.deleteCharAt(compositionBuffer.lastIndex)
            if (compositionBuffer.isEmpty()) {
                currentReading = ""; currentCandidates = emptyList(); currentInputConnection?.setComposingText("", 1); currentInputConnection?.finishComposingText(); compositionContext = ""; postNextPredictions()
            } else refreshCompositionAndCandidates()
            return
        }
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (japaneseMode) postNextPredictions() else postEnglishNextPredictions()
    }

    private fun handleEnter() {
        cancelPendingNextPrediction(); cancelPendingRerank()
        if (!japaneseMode && englishBuffer.isNotEmpty()) commitEnglishBuffer(false)
        if (compositionBuffer.isNotEmpty()) { commitCandidate(currentCandidates.firstOrNull() ?: currentReading); return }
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        if (japaneseMode) postNextPredictions() else postEnglishNextPredictions()
    }

    private fun commitDirect(text: String) {
        if (compositionBuffer.isNotEmpty()) commitCandidate(currentCandidates.firstOrNull() ?: currentReading)
        if (englishBuffer.isNotEmpty()) commitEnglishBuffer(false)
        currentInputConnection?.commitText(text, 1)
        if (japaneseMode) postNextPredictions() else postEnglishNextPredictions()
    }

    private fun clearCompositionState() {
        compositionBuffer.clear(); currentReading = ""; currentCandidates = emptyList(); compositionContext = ""
    }

    private fun toggleMode(toJapanese: Boolean) {
        cancelPendingNextPrediction(); cancelPendingRerank()
        if (compositionBuffer.isNotEmpty()) commitCandidate(currentCandidates.firstOrNull() ?: currentReading)
        if (englishBuffer.isNotEmpty()) commitEnglishBuffer(false)
        japaneseMode = toJapanese; englishShift = false; renderKeyboard()
        if (japaneseMode) postNextPredictions() else postEnglishNextPredictions()
    }

    private fun cancelPendingRerank(incrementEpoch: Boolean = true) {
        pendingMediumRerank?.let(mainHandler::removeCallbacks); pendingMediumRerank = null; if (incrementEpoch) candidateEpoch++
    }

    private fun cancelPendingNextPrediction(incrementEpoch: Boolean = true) {
        pendingNextPrediction?.let(mainHandler::removeCallbacks); pendingNextPrediction = null; if (incrementEpoch) predictionEpoch++
    }

    private fun showCandidates(
        candidates: List<String>,
        aiBadge: String?,
        aiSlots: Set<Int> = if (aiBadge != null) setOf(0) else emptySet(),
        onClick: (String) -> Unit
    ) {
        val row = candidateRow ?: return
        row.removeAllViews()
        candidates.forEachIndexed { index, candidate ->
            val highlighted = aiBadge != null && index in aiSlots
            val label = if (highlighted) "$candidate  $aiBadge" else candidate
            row.addView(TextView(this).apply {
                text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.rgb(235,235,235)); setPadding(16.dp(),0,16.dp(),0)
                setBackgroundColor(if (highlighted) Color.argb(210, 60, 60, 60) else Color.TRANSPARENT)
                setOnClickListener { onClick(candidate) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 42.dp()))
            if (index != candidates.lastIndex) row.addView(View(this).apply { setBackgroundColor(Color.rgb(82,82,82)) }, LinearLayout.LayoutParams(1.dp(),24.dp()).apply { gravity = Gravity.CENTER_VERTICAL })
        }
    }

    private fun textBeforeCursor(): String = currentInputConnection?.getTextBeforeCursor(260, 0)?.toString().orEmpty()
    private fun aiActive(): Boolean = aiEnabledByUser && !secureField && model != null

    private fun isPasswordField(info: EditorInfo): Boolean {
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        val clazz = type and InputType.TYPE_MASK_CLASS
        return when (clazz) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun japaneseRowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60.dp())
    private fun japaneseSideParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.82f)
    private fun japaneseCenterParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.18f)
    private fun qwertyRowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 55.dp())

    /**
     * v0.10.2 bottom-glow renderer. The key area is black at silence. Audio only adds light
     * from the bottom edge upward, rather than painting the whole keyboard a solid colour.
     */
    private class AudioPulseBackgroundView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var audioEnabled = false
        private var target = 0f
        private var smoothed = 0f
        private var beat = 0f
        private var previousTarget = 0f

        fun setPulse(value: Float, enabled: Boolean) {
            audioEnabled = enabled
            val v = value.coerceIn(0f, 1f)
            val rise = (v - previousTarget).coerceAtLeast(0f)
            if (rise > 0.015f) {
                beat = maxOf(beat, (rise * 4.8f + v * 0.30f).coerceIn(0f, 1f))
            }
            previousTarget = v
            target = v
            smoothed = if (target > smoothed) {
                smoothed * 0.18f + target * 0.82f
            } else {
                smoothed * 0.78f + target * 0.22f
            }
            beat *= 0.78f
            if (target < 0.01f && smoothed < 0.018f && beat < 0.018f) {
                smoothed = 0f
                beat = 0f
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            if (!audioEnabled || width <= 0 || height <= 0) return

            val rawEnergy = (smoothed * 0.82f + beat * 0.48f).coerceIn(0f, 1f)
            val energy = if (rawEnergy < 0.018f) 0f
            else ((rawEnergy - 0.018f) / 0.982f).coerceIn(0f, 1f)
            if (energy <= 0f) return

            val cyan = Color.rgb(0, 178, 255)
            val violet = Color.rgb(112, 58, 255)
            val pink = Color.rgb(255, 35, 154)
            val orange = Color.rgb(255, 112, 36)
            val reactive = when {
                energy < 0.34f -> blend(cyan, violet, energy / 0.34f)
                energy < 0.72f -> blend(violet, pink, (energy - 0.34f) / 0.38f)
                else -> blend(pink, orange, (energy - 0.72f) / 0.28f)
            }

            // The glow grows upward with volume, but never fills the whole surface as a flat colour.
            val glowHeight = height * (0.30f + energy * 0.46f + beat * 0.08f)
            val topY = (height - glowHeight).coerceAtLeast(0f)
            val bottomAlpha = (54 + energy * 170 + beat * 24).toInt().coerceIn(0, 248)
            val midAlpha = (bottomAlpha * (0.54f + energy * 0.16f)).toInt()
            val soft = blend(reactive, Color.rgb(32, 30, 82), 0.36f)
            val bright = blend(reactive, Color.WHITE, 0.13f + beat * 0.08f)

            paint.shader = LinearGradient(
                0f,
                topY,
                0f,
                height.toFloat(),
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(soft, (midAlpha * 0.30f).toInt()),
                    withAlpha(reactive, midAlpha),
                    withAlpha(bright, bottomAlpha)
                ),
                floatArrayOf(0f, 0.30f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, topY, width.toFloat(), height.toFloat(), paint)

            // A wide light source just below the keyboard makes it look illuminated from underneath.
            val radius = width * (0.58f + energy * 0.22f + beat * 0.08f)
            paint.shader = RadialGradient(
                width * 0.50f,
                height * 1.04f,
                radius,
                intArrayOf(
                    withAlpha(bright, (bottomAlpha * 0.90f).toInt()),
                    withAlpha(reactive, (bottomAlpha * 0.56f).toInt()),
                    withAlpha(soft, (bottomAlpha * 0.18f).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.30f, 0.66f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, topY, width.toFloat(), height.toFloat(), paint)

            // Short white-hot lift on transients. It fades quickly with the beat envelope.
            if (beat > 0.10f) {
                val beatRadius = width * (0.34f + beat * 0.18f)
                paint.shader = RadialGradient(
                    width * 0.50f,
                    height * 1.02f,
                    beatRadius,
                    intArrayOf(
                        withAlpha(Color.WHITE, (beat * 82f).toInt()),
                        withAlpha(reactive, (beat * 95f).toInt()),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.46f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, topY, width.toFloat(), height.toFloat(), paint)
            }
            paint.shader = null
        }

        private fun blend(a: Int, b: Int, tValue: Float): Int {
            val t = tValue.coerceIn(0f, 1f)
            val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
            val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
            val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
            return Color.rgb(r, g, bl)
        }

        private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
            alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
        )
    }

    companion object {
        // Normal keys no longer carry an opaque black shade. At silence the background itself is black.
        private val KEY_BLACK = Color.TRANSPARENT
        private val GRID_LINE = Color.argb(118, 68, 68, 82)
        private val PRESSED = Color.argb(120, 82, 82, 94)
        private val MODE_PILL = Color.argb(82, 52, 52, 64)
        private val ACCENT = Color.rgb(126, 203, 196)
        private val ACCENT_PRESSED = Color.rgb(96, 173, 166)
        private val EMOJIS = listOf(
            "😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😍","🥰","😘","😎","🤓","🫠","🤔","🤯","😴","😭","😡","🥺","😈","👻","💀",
            "👍","👎","👌","✌️","🤞","🫶","👏","🙏","💪","👀","❤️","🩷","🧡","💛","💚","💙","💜","🖤","🤍","💯","✨","🔥","⚡","🎉","✅","❌","⭐","🌙",
            "🐶","🐱","🐼","🦊","🐸","🍎","🍓","🍔","🍣","🍺","☕","🚗","✈️","🚀","📷","🎥","💻","📱","🎮","🎧","🧠","🤖","⚙️","🔧","📌","📎","✉️"
        )
    }
}
