package com.ikegami.transformerime.audio

/**
 * Process-local, lock-free transport for the live Audio Pulse envelope.
 *
 * AudioPulseService and TransformerImeService normally run in the same app process,
 * so the per-frame level does not need to be persisted through SharedPreferences.
 * Only user settings remain persistent; the hot path stays in memory.
 */
object AudioPulseState {
    @Volatile var active: Boolean = false
    @Volatile var level: Float = 0f

    fun publish(value: Float) {
        level = value.coerceIn(0f, 1f)
    }

    fun reset() {
        level = 0f
        active = false
    }
}
