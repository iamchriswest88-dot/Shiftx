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
}
