package com.marcos.bassenhancer.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.util.Log

private const val TAG = "BassSource"

/** Something that hands raw PCM to a consumer until it is stopped. */
interface AudioSource {
    val sampleRate: Int
    val channels: Int

    /** Blocks on the calling thread until [stop] or a fatal error. */
    fun run(onPcm: (ShortArray, Int) -> Unit)
    fun stop()
}

/**
 * The accurate path: MediaProjection playback capture (API 29+).
 *
 * Only sees apps that permit capture. Players that set ALLOW_CAPTURE_BY_NONE
 * (Spotify and most DRM video) deliver silence here by design, which is what the
 * Visualizer fallback exists for.
 */
class PlaybackCaptureSource(
    private val projection: MediaProjection,
    override val sampleRate: Int = 44100,
) : AudioSource {

    override val channels: Int = 2

    @Volatile
    private var running = false
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun run(onPcm: (ShortArray, Int) -> Unit) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = rec

        // ~12 ms of stereo audio per read: short enough to stay responsive,
        // long enough that we are not spinning on the syscall.
        val chunk = ShortArray(1024)
        rec.startRecording()
        running = true
        try {
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    onPcm(chunk, n)
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read returned $n")
                    break
                }
            }
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            record = null
        }
    }

    override fun stop() {
        running = false
        runCatching { record?.stop() }
    }
}

/**
 * The fallback path: the legacy global-output Visualizer on session 0.
 *
 * No consent dialog, and it sees every app -- but plenty of devices and Android
 * builds hand back a flat line, so it is presented as experimental in the UI.
 */
class VisualizerSource : AudioSource {

    override val sampleRate: Int = 44100
    override val channels: Int = 1

    private var visualizer: Visualizer? = null
    private val lock = Object()

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    override fun run(onPcm: (ShortArray, Int) -> Unit) {
        val captureSize = Visualizer.getCaptureSizeRange()[1]
        val rate = Visualizer.getMaxCaptureRate().coerceAtMost(20000)
        val buf = ShortArray(captureSize)

        val vis = Visualizer(0).apply {
            setEnabled(false)
            setCaptureSize(captureSize)
            setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {
                        val w = wave ?: return
                        val n = minOf(w.size, buf.size)
                        // Visualizer waveform bytes are unsigned 8-bit centred on 128.
                        for (i in 0 until n) {
                            buf[i] = (((w[i].toInt() and 0xFF) - 128) shl 8).toShort()
                        }
                        onPcm(buf, n)
                    }

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) = Unit
                },
                rate,
                true,
                false,
            )
            setEnabled(true)
        }
        visualizer = vis
        running = true

        synchronized(lock) {
            while (running) {
                try {
                    lock.wait(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        runCatching { vis.setEnabled(false) }
        runCatching { vis.release() }
        visualizer = null
    }

    override fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }
}
