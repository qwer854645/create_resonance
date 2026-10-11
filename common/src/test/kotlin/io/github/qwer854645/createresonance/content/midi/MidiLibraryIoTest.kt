package io.github.qwer854645.createresonance.content.midi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MidiLibraryIoTest {
    @Test
    fun `final snapshot follows uploads on the worker instead of the game thread`() {
        val caller = Thread.currentThread()
        val done = CountDownLatch(1)
        val operations = mutableListOf<String>()
        var worker: Thread? = null
        assertTrue(MidiLibraryIo.submit { worker = Thread.currentThread(); operations.add("upload A") })
        assertTrue(MidiLibraryIo.submit { operations.add("upload B") })
        assertTrue(MidiLibraryIo.submit { operations.add("snapshot"); done.countDown() })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotSame(caller, worker)
        assertEquals(listOf("upload A", "upload B", "snapshot"), operations)
    }
}
