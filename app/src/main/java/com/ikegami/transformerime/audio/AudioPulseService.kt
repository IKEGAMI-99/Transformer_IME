package com.ikegami.transformerime.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Captures capturable system playback and publishes only a normalized 0..1 pulse level. */
class AudioPulseService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureLock = Any()

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs().edit().putBoolean(KEY_ENABLED, false).apply()
            stopCapture(stopProjection = true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        // MediaProjection grants cannot be reconstructed after the service/process is killed.
        // START_STICKY therefore only created a zombie "enabled" state with no capture token.
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            markInactive(disableFeature = true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundPulse()
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startForegroundPulse() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio Pulse", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keyboard audio-reactive background"
                setSound(null, null)
                enableVibration(false)
            }
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Transformer IME Audio Pulse")
            .setContentText("システムオーディオにキーボード背景を同期中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull() ?: run {
            markInactive(disableFeature = true)
            stopSelf()
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(captureLock) { projection = null }
                stopCapture(stopProjection = false)
                prefs().edit().putBoolean(KEY_ENABLED, false).apply()
                stopSelf()
            }
        }, mainHandler)

        val config = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
        }.getOrElse {
            failCapture()
            return
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minimum * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.getOrElse {
            failCapture()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            failCapture()
            return
        }

        synchronized(captureLock) {
            audioRecord = record
            running = true
        }

        val accepted = runCatching {
            executor.execute { captureLoop(record) }
            true
        }.getOrDefault(false)
        if (!accepted) failCapture()
    }

    private fun captureLoop(record: AudioRecord) {
        val buffer = ShortArray(2048)
        var envelope = 0f
        val started = runCatching {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
        if (!started) {
            mainHandler.post { failCapture() }
            return
        }

        AudioPulseState.active = true
        AudioPulseState.publish(0f)
        prefs().edit().putBoolean(KEY_ACTIVE, true).apply()

        var captureFailed = false
        while (running) {
            val read = runCatching {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            }.getOrElse {
                captureFailed = true
                break
            }

            if (read == AudioRecord.ERROR_DEAD_OBJECT ||
                read == AudioRecord.ERROR_INVALID_OPERATION ||
                read == AudioRecord.ERROR_BAD_VALUE
            ) {
                captureFailed = true
                break
            }
            if (read <= 0) {
                // Never spin a core at 100% when an OEM temporarily returns an empty buffer.
                runCatching { Thread.sleep(8L) }
                continue
            }

            var sum = 0.0
            var samplePeak = 0.0
            for (i in 0 until read) {
                val x = buffer[i] / 32768.0
                sum += x * x
                samplePeak = maxOf(samplePeak, abs(x))
            }

            val rms = sqrt(sum / read).toFloat()
            val rmsDb = (20.0 * log10(rms.coerceAtLeast(0.00001f).toDouble())).toFloat()
            val rmsLevel = ((rmsDb + 48f) / 45f).coerceIn(0f, 1f)
            val peakLevel = ((samplePeak.toFloat() - 0.04f) / 0.92f).coerceIn(0f, 1f)
            val mixed = (rmsLevel * 0.82f + peakLevel * 0.18f).coerceIn(0f, 1f)
            val gated = if (mixed < 0.045f) 0f else ((mixed - 0.045f) / 0.955f).coerceIn(0f, 1f)
            val reactive = gated.pow(1.45f)

            envelope = if (reactive > envelope) {
                envelope * 0.14f + reactive * 0.86f
            } else {
                envelope * 0.84f + reactive * 0.16f
            }
            if (reactive == 0f && envelope < 0.010f) envelope = 0f
            AudioPulseState.publish(envelope)
        }

        if (captureFailed && running) {
            mainHandler.post { failCapture() }
        }
    }

    private fun failCapture() {
        prefs().edit().putBoolean(KEY_ENABLED, false).apply()
        stopCapture(stopProjection = true)
        stopSelf()
    }

    private fun markInactive(disableFeature: Boolean) {
        AudioPulseState.reset()
        prefs().edit()
            .putBoolean(KEY_ACTIVE, false)
            .apply {
                if (disableFeature) putBoolean(KEY_ENABLED, false)
            }
            .apply()
    }

    private fun stopCapture(stopProjection: Boolean) {
        val record: AudioRecord?
        val activeProjection: MediaProjection?
        synchronized(captureLock) {
            running = false
            record = audioRecord
            audioRecord = null
            activeProjection = projection
            projection = null
        }
        AudioPulseState.reset()
        prefs().edit().putBoolean(KEY_ACTIVE, false).apply()
        runCatching { record?.stop() }
        runCatching { record?.release() }
        if (stopProjection) runCatching { activeProjection?.stop() }
    }

    override fun onDestroy() {
        stopCapture(stopProjection = true)
        // A destroyed MediaProjection service cannot resume silently; make the persisted switch
        // honest instead of leaving it visually enabled with a dead capture session.
        prefs().edit().putBoolean(KEY_ENABLED, false).apply()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val ACTION_STOP = "com.ikegami.transformerime.STOP_AUDIO_PULSE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PREFS = "transformer_ime"
        const val KEY_ENABLED = "audio_pulse_enabled"
        const val KEY_ACTIVE = "audio_pulse_active"
        const val KEY_LEVEL = "audio_pulse_level"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_ID = "audio_pulse"
        private const val NOTIFICATION_ID = 4401
    }
}
