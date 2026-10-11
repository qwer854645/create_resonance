package io.github.qwer854645.createresonance.audio.midi

import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

/** Indexed channel state, without notes. Restoring a part never replays old note-ons. */
internal class MidiStateTimeline(sequence: Sequence) {
    private data class Change(val tick: Long, val order: Int, val messages: List<ShortMessage>)

    private val changes = mutableMapOf<Int, MutableList<Change>>()

    init {
        val bankMsb = IntArray(16)
        val bankLsb = IntArray(16)
        val events = sequence.tracks.flatMap { track -> (0 until track.size()).map { track[it] } }
            .sortedBy { it.tick }
        events.forEachIndexed { order, event ->
            val message = event.message as? ShortMessage ?: return@forEachIndexed
            val channel = message.channel
            val command = message.command
            val key = when (command) {
                ShortMessage.CONTROL_CHANGE -> {
                    if (message.data1 >= 120) return@forEachIndexed
                    if (message.data1 == 0) bankMsb[channel] = message.data2
                    if (message.data1 == 32) bankLsb[channel] = message.data2
                    channel * 256 + message.data1
                }
                ShortMessage.PROGRAM_CHANGE -> channel * 256 + 128
                ShortMessage.PITCH_BEND -> channel * 256 + 129
                ShortMessage.CHANNEL_PRESSURE -> channel * 256 + 130
                else -> return@forEachIndexed
            }
            // A program uses the bank selected at that event, even if a later bank select
            // has not yet been followed by another program change.
            val messages = if (command == ShortMessage.PROGRAM_CHANGE) {
                listOf(
                    ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 0, bankMsb[channel]),
                    ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 32, bankLsb[channel]),
                    message.clone() as ShortMessage,
                )
            } else {
                listOf(message.clone() as ShortMessage)
            }
            changes.getOrPut(key) { mutableListOf() }.add(Change(event.tick, order, messages))
        }
    }

    /** Binary search each controller history; work does not grow with elapsed playback. */
    fun at(tick: Long): List<ShortMessage> = changes.values.mapNotNull { history ->
        var low = 0
        var high = history.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (history[mid].tick <= tick) low = mid + 1 else high = mid
        }
        history.getOrNull(low - 1)
    }.sortedBy { it.order }.flatMap { it.messages }
}
