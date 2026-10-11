package io.github.qwer854645.createresonance.content.midi

import io.github.qwer854645.createresonance.foundation.warn
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** FIFO keeps an upload batch ahead of its final listing, without blocking game ticks. */
object MidiLibraryIo {
    private val worker = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        { task -> Thread(task, "Resonance MIDI library IO").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    fun submit(task: () -> Unit): Boolean = try {
        worker.execute {
            try {
                task()
            } catch (e: Exception) {
                "MIDI library operation failed: ${e.message}".warn()
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }
}
