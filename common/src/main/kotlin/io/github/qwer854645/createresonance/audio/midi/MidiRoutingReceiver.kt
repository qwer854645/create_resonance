package io.github.qwer854645.createresonance.audio.midi

import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/** Keep source channels isolated, even when every part uses the same instrument. */
internal class MidiRoutingReceiver(
    private val delegate: Receiver,
    private val allowed: () -> Set<Int>?,
    private val forcedProgram: () -> Int?,
    private val muted: () -> Boolean,
    private val state: () -> List<ShortMessage>,
) : Receiver {
    @Synchronized
    override fun send(message: MidiMessage, timeStamp: Long) {
        if (muted()) return
        if (message is ShortMessage && message.command < 0xF0) {
            if (allowed()?.let { message.channel !in it } == true) return
            if (forcedProgram() != null) {
                if (message.channel == 9) return
                if (message.command == ShortMessage.PROGRAM_CHANGE) return
                if (message.command == ShortMessage.CONTROL_CHANGE && (message.data1 == 0 || message.data1 == 32)) return
            }
        }
        delegate.send(message, timeStamp)
    }

    /** Serialize GUI changes with the sequencer's receiver thread, without restarting its clock. */
    @Synchronized
    fun reconfigure(change: () -> Unit) {
        silence()
        change()
        restoreState()
    }

    @Synchronized
    fun silence() {
        for (channel in 0..15) {
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 64, 0), -1)
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 123, 0), -1)
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 120, 0), -1)
        }
    }

    @Synchronized
    fun restoreState() {
        for (channel in 0..15) {
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 121, 0), -1)
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 7, 100), -1)
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 10, 64), -1)
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 11, 127), -1)
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 0, 0), -1)
            delegate.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 32, 0), -1)
            delegate.send(ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, 0, 0), -1)
            delegate.send(ShortMessage(ShortMessage.PITCH_BEND, channel, 0, 64), -1)
            delegate.send(ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, 0, 0), -1)
        }
        val program = forcedProgram()
        for (message in state()) {
            if (program != null && (message.command == ShortMessage.PROGRAM_CHANGE ||
                    message.command == ShortMessage.CONTROL_CHANGE && (message.data1 == 0 || message.data1 == 32))) continue
            delegate.send(message, -1)
        }
        if (program != null) {
            for (channel in 0..15) {
                if (channel != 9) delegate.send(ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0), -1)
            }
        }
    }

    @Synchronized
    override fun close() = delegate.close()
}
