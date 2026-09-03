package com.trailback.app.data.repository
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class TrackingStateStoreTest {
    private lateinit var store: TrackingStateStore
    @Before
    fun setUp() {
        store = TrackingStateStore(ApplicationProvider.getApplicationContext())
        store.reset()
    }
    @Test
    fun `no recoverable track when mode is IDLE`() {
        store.mode = TrackingMode.IDLE
        store.activeEntryPointId = 1L
        store.lastUpdateTimestamp = System.currentTimeMillis()
        assertFalse(store.hasRecoverableTrack(System.currentTimeMillis()))
    }
    @Test
    fun `recoverable when RECORDING and within 72 hours`() {
        val now = System.currentTimeMillis()
        store.mode = TrackingMode.RECORDING
        store.activeEntryPointId = 1L
        store.lastUpdateTimestamp = now - (71L * 60 * 60 * 1000) // 71 час назад
        assertTrue(store.hasRecoverableTrack(now))
    }
    @Test
    fun `recoverable when RETURNING and within 72 hours`() {
        val now = System.currentTimeMillis()
        store.mode = TrackingMode.RETURNING
        store.activeEntryPointId = 1L
        store.lastUpdateTimestamp = now - (1L * 60 * 60 * 1000) // 1 час назад
        assertTrue(store.hasRecoverableTrack(now))
    }
    @Test
    fun `not recoverable after 72 hours window expired`() {
        val now = System.currentTimeMillis()
        store.mode = TrackingMode.RECORDING
        store.activeEntryPointId = 1L
        store.lastUpdateTimestamp = now - (73L * 60 * 60 * 1000) // 73 часа назад — окно истекло
        assertFalse(store.hasRecoverableTrack(now))
    }
    @Test
    fun `exactly at 72 hour boundary is still recoverable (inclusive)`() {
        val now = System.currentTimeMillis()
        store.mode = TrackingMode.RECORDING
        store.activeEntryPointId = 1L
        store.lastUpdateTimestamp = now - TrackingStateStore.RECOVERY_WINDOW_MILLIS
        assertTrue(store.hasRecoverableTrack(now))
    }
    @Test
    fun `no recoverable track without an active entry point`() {
        store.mode = TrackingMode.RECORDING
        store.activeEntryPointId = -1L
        store.lastUpdateTimestamp = System.currentTimeMillis()
        assertFalse(store.hasRecoverableTrack(System.currentTimeMillis()))
    }
    @Test
    fun `reset clears mode and entry point`() {
        store.mode = TrackingMode.RECORDING
        store.activeEntryPointId = 5L
        store.distanceMeters = 1234f
        store.reset()
        assertTrue(store.mode == TrackingMode.IDLE)
        assertTrue(store.activeEntryPointId == -1L)
    }
}
