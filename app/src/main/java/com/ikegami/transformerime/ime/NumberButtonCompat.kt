package com.ikegami.transformerime.ime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/**
 * One-argument overload used by the numeric fan trigger.
 * The main IME's richer functionButton requires an action; numberMenuButton installs tap and
 * long-press handlers immediately afterwards, so this overload only supplies the visual shell.
 */
internal fun TransformerImeService.functionButton(label: String): Button = Button(this).apply {
    text = label
    textSize = 18f
    setTextColor(Color.rgb(238, 238, 238))
    isAllCaps = false
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    setPadding(0, 0, 0, 0)
    background = GradientDrawable().apply {
        setColor(Color.argb(225, 0, 0, 0))
        setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.argb(180, 40, 40, 40))
    }
}
