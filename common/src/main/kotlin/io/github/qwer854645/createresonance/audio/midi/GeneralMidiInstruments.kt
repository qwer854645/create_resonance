package io.github.qwer854645.createresonance.audio.midi

import net.minecraft.network.chat.Component

/**
 * General MIDI program numbers (0–127) for music-box instrument override,
 * plus [SCORE_PROGRAM] to keep each channel's instruments from the MIDI file.
 */
object GeneralMidiInstruments {
    const val COUNT = 128

    /** Sentinel: play with Program Changes / banks written in the score. */
    const val SCORE_PROGRAM = -1

    /** Default: GM Music Box — fits the block theme. */
    const val DEFAULT_PROGRAM = 10

    /** Cycle length including score-native mode. */
    const val CYCLE_COUNT = COUNT + 1

    private val ENGLISH =
        arrayOf(
            "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
            "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
            "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
            "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
            "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
            "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
            "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)", "Electric Guitar (clean)",
            "Electric Guitar (muted)", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
            "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
            "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
            "Violin", "Viola", "Cello", "Contrabass",
            "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
            "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
            "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
            "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
            "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
            "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
            "Oboe", "English Horn", "Bassoon", "Clarinet",
            "Piccolo", "Flute", "Recorder", "Pan Flute",
            "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
            "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
            "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass + lead)",
            "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
            "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
            "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
            "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
            "Sitar", "Banjo", "Shamisen", "Koto",
            "Kalimba", "Bagpipe", "Fiddle", "Shanai",
            "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
            "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
            "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
            "Telephone Ring", "Helicopter", "Applause", "Gunshot",
        )

    fun clamp(program: Int): Int = program.coerceIn(0, COUNT - 1)

    fun isScoreNative(program: Int): Boolean = program < 0

    /** Normalize stored value: negative → score mode, else GM 0–127. */
    fun normalize(program: Int): Int = if (program < 0) SCORE_PROGRAM else clamp(program)

    /**
     * Cycle through score-native mode and GM programs 0–127.
     * Order: Score ↔ 0 ↔ 1 ↔ … ↔ 127 ↔ Score …
     */
    fun cycle(
        current: Int,
        delta: Int,
    ): Int {
        val idx = if (isScoreNative(current)) 0 else clamp(current) + 1
        val next = (idx + delta).mod(CYCLE_COUNT)
        return if (next == 0) SCORE_PROGRAM else next - 1
    }

    fun englishName(program: Int): String =
        if (isScoreNative(program)) "Score instruments" else ENGLISH[clamp(program)]

    fun displayName(program: Int): Component {
        if (isScoreNative(program)) {
            return Component.translatable("create_resonance.gui.music_box.instrument_score")
        }
        val id = clamp(program)
        return Component.translatableWithFallback(
            "create_resonance.midi.instrument.$id",
            ENGLISH[id],
        )
    }
}
