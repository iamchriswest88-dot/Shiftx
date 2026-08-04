package com.example.shift.ui.runner

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Provides audio cues for the Runner using ToneGenerator (no audio files needed).
 * Mutable [muted] flag — honour it before every play call.
 */
class AudioCueManager(context: Context) {

    var muted: Boolean = false

    // Use STREAM_MUSIC at max volume; create fresh generator per instance
    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)

    /** Single beep — played when a WORK phase starts. */
    fun playWorkStart() = play(ToneGenerator.TONE_PROP_BEEP, 150)

    /** Short click tick — played at 3, 2, 1 seconds remaining in WORK and SWAP phases. */
    fun playTick() = play(ToneGenerator.TONE_DTMF_1, 80)

    /** Double beep — played at end of a WORK phase. */
    fun playWorkEnd() {
        if (muted) return
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
    }

    /** Single lower beep — played at end of a REST phase. */
    fun playRestEnd() = play(ToneGenerator.TONE_PROP_BEEP, 100)

    fun release() {
        try { toneGen.release() } catch (e: Exception) { /* ignore */ }
    }

    private fun play(type: Int, durationMs: Int) {
        if (muted) return
        try { toneGen.startTone(type, durationMs) } catch (e: Exception) { /* ignore */ }
    }
}
