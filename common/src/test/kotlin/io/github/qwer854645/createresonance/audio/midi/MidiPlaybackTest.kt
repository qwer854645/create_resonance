package io.github.qwer854645.createresonance.audio.midi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

class MidiPlaybackTest {
    private class Sink : Receiver {
        val messages = mutableListOf<ShortMessage>()
        override fun send(message: MidiMessage, timeStamp: Long) {
            if (message is ShortMessage) messages.add(message.clone() as ShortMessage)
        }
        override fun close() {}
    }

    private fun message(command: Int, channel: Int, a: Int, b: Int = 0) = ShortMessage(command, channel, a, b)

    @Test
    fun `same pitch in different parts keeps independent note offs and sustain`() {
        val sink = Sink()
        val receiver = MidiRoutingReceiver(sink, { null }, { 10 }, { false }, { emptyList() })
        receiver.send(message(ShortMessage.NOTE_ON, 1, 60, 100), -1)
        receiver.send(message(ShortMessage.NOTE_ON, 2, 60, 100), -1)
        receiver.send(message(ShortMessage.CONTROL_CHANGE, 1, 64, 127), -1)
        receiver.send(message(ShortMessage.NOTE_OFF, 1, 60), -1)
        assertEquals(listOf(1, 2, 1, 1), sink.messages.map { it.channel })
    }

    @Test
    fun `forced mode blocks file programs and percussion but native mode keeps both`() {
        val sink = Sink()
        var forced: Int? = 10
        val receiver = MidiRoutingReceiver(sink, { null }, { forced }, { false }, { emptyList() })
        receiver.send(message(ShortMessage.PROGRAM_CHANGE, 2, 40), -1)
        receiver.send(message(ShortMessage.NOTE_ON, 9, 36, 100), -1)
        assertTrue(sink.messages.isEmpty())
        forced = null
        receiver.send(message(ShortMessage.PROGRAM_CHANGE, 2, 40), -1)
        receiver.send(message(ShortMessage.NOTE_ON, 9, 36, 100), -1)
        assertEquals(2, sink.messages.size)
    }

    @Test
    fun `switch to score instruments restores original banks programs and controllers`() {
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        track.add(MidiEvent(message(ShortMessage.CONTROL_CHANGE, 2, 0, 3), 0))
        track.add(MidiEvent(message(ShortMessage.PROGRAM_CHANGE, 2, 40), 0))
        track.add(MidiEvent(message(ShortMessage.CONTROL_CHANGE, 2, 7, 85), 10))
        track.add(MidiEvent(message(ShortMessage.NOTE_ON, 2, 60, 100), 10))
        val timeline = MidiStateTimeline(sequence)
        val sink = Sink()
        var forced: Int? = 10
        val receiver = MidiRoutingReceiver(sink, { null }, { forced }, { false }, { timeline.at(20) })
        receiver.reconfigure { forced = null }
        assertEquals(40, sink.messages.last { it.channel == 2 && it.command == ShortMessage.PROGRAM_CHANGE }.data1)
        assertEquals(3, sink.messages.last { it.channel == 2 && it.command == ShortMessage.CONTROL_CHANGE && it.data1 == 0 }.data2)
        assertEquals(85, sink.messages.last { it.channel == 2 && it.command == ShortMessage.CONTROL_CHANGE && it.data1 == 7 }.data2)
        assertFalse(sink.messages.any { it.command == ShortMessage.NOTE_ON })
    }

    @Test
    fun `rewinding excludes future program changes`() {
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        track.add(MidiEvent(message(ShortMessage.PROGRAM_CHANGE, 0, 40), 0))
        track.add(MidiEvent(message(ShortMessage.PROGRAM_CHANGE, 0, 73), 100))
        val timeline = MidiStateTimeline(sequence)
        assertEquals(73, timeline.at(200).last { it.command == ShortMessage.PROGRAM_CHANGE }.data1)
        assertEquals(40, timeline.at(50).last { it.command == ShortMessage.PROGRAM_CHANGE }.data1)
    }

    @Test
    fun `a bank selected after a program is restored after that program`() {
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        track.add(MidiEvent(message(ShortMessage.CONTROL_CHANGE, 1, 0, 2), 0))
        track.add(MidiEvent(message(ShortMessage.PROGRAM_CHANGE, 1, 40), 10))
        track.add(MidiEvent(message(ShortMessage.CONTROL_CHANGE, 1, 0, 5), 20))
        val restored = MidiStateTimeline(sequence).at(30)
        val pc = restored.indexOfFirst { it.command == ShortMessage.PROGRAM_CHANGE }
        assertEquals(2, restored[pc - 2].data2)
        assertEquals(5, restored.last().data2)
    }

    @Test
    fun `part changes silence disabled channels even when muted`() {
        val sink = Sink()
        var allowed: Set<Int>? = setOf(1)
        val receiver = MidiRoutingReceiver(sink, { allowed }, { null }, { true }, { emptyList() })
        receiver.reconfigure { allowed = setOf(2) }
        assertEquals((0..15).toList(), sink.messages.filter {
            it.command == ShortMessage.CONTROL_CHANGE && it.data1 == 120
        }.map { it.channel })
    }

    @Test
    fun `zero rpm freezes progress and cannot prematurely finish`() {
        val head = advancePlaybackHead(12.0, 0, 12000, playbackClockRate(0f), true)
        assertEquals(12.0, head, 0.0001)
        assertFalse(playbackHasEnded(true, head, 60.0))
    }

    @Test
    fun `speed changes preserve time already played at the previous speed`() {
        val stoppedAt = advancePlaybackHead(0.0, 0, 200, playbackClockRate(64f), true)
        val resumedAt = advancePlaybackHead(stoppedAt, 200, 400, playbackClockRate(0f), true)
        val acceleratedAt = advancePlaybackHead(resumedAt, 400, 500, playbackClockRate(128f), true)
        assertEquals(10.0, stoppedAt, 0.0001)
        assertEquals(10.0, resumedAt, 0.0001)
        assertEquals(20.0, acceleratedAt, 0.0001)
    }

    @Test
    fun `client completion hints cannot finish an early paused or unknown track`() {
        assertFalse(playbackHasEnded(true, 1.0, 60.0))
        assertFalse(playbackHasEnded(false, 60.0, 60.0))
        assertFalse(playbackHasEnded(true, 60.0, 0.0))
        assertTrue(playbackHasEnded(true, 60.0, 60.0))
    }

    @Test
    fun `fallback banks release repeated pitches in their assigned order`() {
        val first = Sink()
        val second = Sink()
        val receiver = PolyphonyFanoutReceiver(listOf(first, second))
        receiver.send(message(ShortMessage.NOTE_ON, 1, 60, 100), -1)
        receiver.send(message(ShortMessage.NOTE_ON, 1, 60, 100), -1)
        receiver.send(message(ShortMessage.NOTE_OFF, 1, 60), -1)
        receiver.send(message(ShortMessage.NOTE_OFF, 1, 60), -1)
        assertEquals(1, first.messages.count { it.command == ShortMessage.NOTE_OFF })
        assertEquals(1, second.messages.count { it.command == ShortMessage.NOTE_OFF })
    }

    @Test
    fun `rewinding before volume change restores the default volume`() {
        val sequence = Sequence(Sequence.PPQ, 480)
        sequence.createTrack().add(MidiEvent(message(ShortMessage.CONTROL_CHANGE, 0, 7, 20), 100))
        val timeline = MidiStateTimeline(sequence)
        val sink = Sink()
        var tick = 200L
        val receiver = MidiRoutingReceiver(sink, { null }, { null }, { false }, { timeline.at(tick) })
        receiver.restoreState()
        tick = 0L
        receiver.reconfigure {}
        assertEquals(100, sink.messages.last {
            it.channel == 0 && it.command == ShortMessage.CONTROL_CHANGE && it.data1 == 7
        }.data2)
    }
}
