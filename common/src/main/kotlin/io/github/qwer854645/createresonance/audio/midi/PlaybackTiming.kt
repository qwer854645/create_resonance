package io.github.qwer854645.createresonance.audio.midi

import kotlin.math.abs

/** Stopped kinetics freeze the authoritative timeline; active speeds match the audio engine. */
internal fun playbackClockRate(rpm: Float): Double =
    if (rpm == 0f || !rpm.isFinite()) 0.0 else (abs(rpm) / 64.0).coerceIn(0.25, 4.0)

internal fun advancePlaybackHead(head: Double, startTick: Long, nowTick: Long, rate: Double, playing: Boolean): Double =
    (head + if (playing) (nowTick - startTick).coerceAtLeast(0L) / 20.0 * rate else 0.0).coerceAtLeast(0.0)

internal fun playbackHasEnded(playing: Boolean, head: Double, duration: Double): Boolean =
    playing && head.isFinite() && duration.isFinite() && duration >= 0.5 && head >= 0.25 && head >= duration - 0.05
