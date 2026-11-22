package com.scantrader.app

import com.scantrader.app.scan.ScanBitmapLease
import com.scantrader.app.scan.ScanErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBitmapLeaseTest {
    private class Fake(val name: String) {
        var freed = 0
        override fun equals(other: Any?): Boolean = other is Fake && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }

    @Test
    fun sameObjectIsFreedOnceAndEqualButDistinctObjectsBoth() {
        val photo = Fake("x")
        val twin = Fake("x")
        val crop = Fake("crop")
        val lease = ScanBitmapLease<Fake> { it.freed++ }
        // ensurePortrait / createBitmap can hand back the same object.
        lease.track(photo, photo, crop, null).track(twin, crop)
        assertEquals(3, lease.size())
        assertEquals(3, lease.releaseAll())
        assertEquals(1, photo.freed)
        assertEquals(1, twin.freed)
        assertEquals(1, crop.freed)
        assertEquals(0, lease.releaseAll())
        assertEquals(1, photo.freed)
    }

    @Test
    fun abandonedLeaseFreesNothingAndIgnoresLateTracks() {
        val photo = Fake("p")
        val lease = ScanBitmapLease<Fake> { it.freed++ }
        lease.track(photo)
        lease.abandon()
        lease.track(Fake("late"))
        assertEquals(0, lease.releaseAll())
        assertEquals(0, photo.freed)
    }

    @Test
    fun oneFailingFreeDoesNotKeepTheOthers() {
        val a = Fake("a")
        val b = Fake("b")
        val lease = ScanBitmapLease<Fake> {
            if (it === a) error("already recycled")
            it.freed++
        }
        lease.track(a, b)
        assertEquals(2, lease.releaseAll())
        assertEquals(1, b.freed)
    }

    @Test
    fun outOfMemoryInTheImageStepFallsBackAndOtherErrorsStillThrow() {
        var logged = false
        val ranked = ScanErrors.orOnOutOfMemory(emptyList<String>(), onOom = { logged = true }) {
            throw OutOfMemoryError("Failed to allocate a 12279824 byte allocation")
        }
        assertTrue(ranked.isEmpty())
        assertTrue(logged)
        assertEquals(listOf("hit"), ScanErrors.orOnOutOfMemory(emptyList()) { listOf("hit") })
        val thrown = runCatching {
            ScanErrors.orOnOutOfMemory(emptyList<String>()) { throw IllegalStateException("boom") }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun confirmScreenNeverShowsTheRawAllocatorText() {
        val raw = OutOfMemoryError(
            "Failed to allocate a 12279824 byte allocation with 10129680 free bytes and 9892KB until OOM",
        )
        assertEquals(ScanErrors.OUT_OF_MEMORY, ScanErrors.message(raw))
        assertEquals(ScanErrors.OUT_OF_MEMORY, ScanErrors.message(RuntimeException("wrapped", raw)))
        assertEquals("token API mancante", ScanErrors.message(IllegalStateException("token API mancante")))
        assertEquals("Errore durante lo scan", ScanErrors.message(RuntimeException()))
    }
}
