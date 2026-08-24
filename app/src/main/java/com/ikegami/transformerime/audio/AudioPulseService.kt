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
import kotlin.math.sqrt

/** Captures capturable system playback and publishes only a normalized 0..1 pulse level. */
class AudioPulseService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture(stopProjection = true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundPulse()
        startCapture(resultCode, data)
        return START_STICKY
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
            .setContentText("システムオーディオに背景を同期中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                stopCapture(stopProjection = false)
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
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
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimum * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        audioRecord = record
        running = true
        prefs().edit().putBoolean(KEY_ACTIVE, true).apply()
        executor.execute {
            val buffer = ShortArray(2048)
            var smoothed = 0f
            runCatching { record.startRecording() }
            while (running) {
                val read = runCatching { record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }.getOrDefault(0)
                if (read <= 0) continue
                var sum = 0.0
                for (i in 0 until read) {
                    val x = buffer[i] / 32768.0
                    sum += x * x
                }
                val rms = sqrt(sum / read).toFloat()
                val normalized = (rms * 8.5f).coerceIn(0f, 1f)
                smoothed = if (normalized > smoothed) smoothed * 0.40f + normalized * 0.60f
                else smoothed * 0.86f + normalized * 0.14f
                prefs().edit().putFloat(KEY_LEVEL, smoothed).apply()
            }
        }
    }

    private fun stopCapture(stopProjection: Boolean) {
        running = false
        prefs().edit().putFloat(KEY_LEVEL, 0f).putBoolean(KEY_ACTIVE, false).apply()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        val activeProjection = projection
        projection = null
        if (stopProjection) runCatching { activeProjection?.stop() }
    }

    override fun onDestroy() {
        stopCapture(stopProjection = true)
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
