package com.agentos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The short sounds for dictation: one as the mic opens, one as the words are sent. The phone's own recognizer plays
 * its own, but OpenAI dictation records the mic directly ([CloudVoice.dictate]) and so played nothing.
 *
 * They play on the assistant's own stream (USAGE_ASSISTANT, like Ubiq's spoken replies), so they follow the media
 * volume the volume keys set: muted media means no sound. Not system sounds: those follow the ringer, and a phone kept
 * on vibrate never heard them. Made from sine tones here: no sound files to ship.
 */
object Earcons {
    private const val RATE = 44_100

    /** Two notes going up: the mic is open. Returns how long it plays, so recording can start after it. */
    fun start(ctx: Context): Long = play(ctx, listOf(784.0 to 70, 1175.0 to 90))

    /** Two notes going down: the words are on their way. */
    fun send(ctx: Context): Long = play(ctx, listOf(1175.0 to 60, 880.0 to 90))

    private fun play(ctx: Context, notes: List<Pair<Double, Int>>): Long {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return 0
        val pcm = tones(notes)
        return runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track.write(pcm, 0, pcm.size)
            // Released once it has played (a static track keeps its buffer until then).
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            track.play()
            pcm.size * 1000L / RATE
        }.getOrDefault(0)
    }

    /** Soft sine notes, each with a quick attack and a decaying tail, so nothing clicks. */
    private fun tones(notes: List<Pair<Double, Int>>): ShortArray {
        val out = ShortArray(notes.sumOf { RATE * it.second / 1000 })
        var at = 0
        for ((hz, ms) in notes) {
            val n = RATE * ms / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val env = min(1.0, i / (RATE * 0.004)) * exp(-t * 18.0) * min(1.0, (n - i) / (RATE * 0.006))
                out[at + i] = (sin(2 * PI * hz * t) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
            }
            at += n
        }
        return out
    }
}
