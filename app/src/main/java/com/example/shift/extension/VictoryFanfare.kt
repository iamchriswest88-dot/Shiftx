package com.example.shift.extension

import io.hammerhead.karooext.models.PlayBeepPattern

/**
 * Segment-completion fanfares for the Karoo's buzzer.
 *
 * Played through karoo-ext's PlayBeepPattern, so there is no audio asset and no
 * media permission — just a list of (frequency, duration) tones, with a null
 * frequency meaning a rest.
 *
 * These are original fanfares written in the classic victory shape (three short
 * notes into a long one, then a rising resolution) rather than a transcription of
 * any published game theme, since this repository is public. The tables are plain
 * data — swap the numbers for whatever melody you like.
 */
object VictoryFanfare {

    // Equal-temperament frequencies, rounded to whole Hz.
    private const val C5 = 523
    private const val E5 = 659
    private const val G5 = 784
    private const val A5 = 880
    private const val C6 = 1047
    private const val D6 = 1175
    private const val E6 = 1319
    private const val G6 = 1568

    private fun tone(freq: Int?, ms: Int) = PlayBeepPattern.Tone(freq, ms)

    /**
     * Standard finish: three short notes, a held fifth, then a rise to the octave.
     * About 1.2 s, short enough not to mask the finish alert.
     */
    val finish: PlayBeepPattern = PlayBeepPattern(
        listOf(
            tone(C5, 90),
            tone(null, 40),
            tone(C5, 90),
            tone(null, 40),
            tone(C5, 90),
            tone(G5, 300),
            tone(null, 60),
            tone(E5, 110),
            tone(G5, 110),
            tone(C6, 420)
        )
    )

    /**
     * Personal best: the same opening, carried up an extra octave so a PR is
     * audibly different from an ordinary finish without watching the screen.
     */
    val personalBest: PlayBeepPattern = PlayBeepPattern(
        listOf(
            tone(C5, 90),
            tone(null, 40),
            tone(C5, 90),
            tone(null, 40),
            tone(C5, 90),
            tone(G5, 280),
            tone(null, 60),
            tone(E5, 100),
            tone(G5, 100),
            tone(C6, 200),
            tone(null, 50),
            tone(A5, 100),
            tone(C6, 100),
            tone(D6, 100),
            tone(E6, 220),
            tone(null, 40),
            tone(G6, 520)
        )
    )

    fun forFinish(isNewPr: Boolean): PlayBeepPattern = if (isNewPr) personalBest else finish

    /**
     * Parses a user-supplied melody: "frequency:milliseconds" pairs separated by
     * commas, spaces or newlines, where frequency 0 (or "r") is a rest.
     *
     *     523:90, 0:40, 523:90, 784:300, 1047:420
     *
     * Returns null for blank or unusable input so the caller can fall back to the
     * built-in fanfare. Malformed pairs are skipped rather than failing the whole
     * melody — a typo should cost a note, not the sound.
     */
    fun parse(pattern: String): PlayBeepPattern? {
        if (pattern.isBlank()) return null

        val tones = pattern
            .split(',', '\n', ' ', '\t')
            .mapNotNull { raw ->
                val part = raw.trim()
                if (part.isEmpty()) return@mapNotNull null
                val bits = part.split(':')
                if (bits.size != 2) return@mapNotNull null

                val freqText = bits[0].trim().lowercase()
                val durationMs = bits[1].trim().toIntOrNull() ?: return@mapNotNull null
                if (durationMs <= 0) return@mapNotNull null

                val isRest = freqText == "r" || freqText == "rest" || freqText == "0"
                val frequency = if (isRest) null else freqText.toIntOrNull() ?: return@mapNotNull null
                // Outside this range the buzzer has nothing useful to play.
                if (frequency != null && frequency !in 40..8000) return@mapNotNull null

                tone(frequency, durationMs.coerceAtMost(3000))
            }
            // Bounded so a pasted wall of numbers cannot buzz for a minute.
            .take(64)

        return if (tones.isEmpty()) null else PlayBeepPattern(tones)
    }
}
