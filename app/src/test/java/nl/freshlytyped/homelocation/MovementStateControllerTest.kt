package nl.freshlytyped.homelocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementStateControllerTest {

    private val controller =
        MovementStateController(MovementConfig(movementDetectionDistanceMeters = 100f, burstDurationMs = 10 * 60_000L))
    private val now = 1_000_000_000_000L

    /** 0.001 degree of latitude is about 111 m, above the 100 m threshold. */
    private fun fix(movedDegrees: Double = 0.0, time: Long = now) =
        LocationFix(52.0 + movedDegrees, 5.0, 10f, time)

    private fun observeFix(
        state: MovementState,
        movedDegrees: Double = 0.0,
        accumulated: Float = 0f,
        lastEvidenceTime: Long = 0L,
        hasLastKnown: Boolean = true
    ) = controller.observe(
        MovementEvidence.Fix(fix(movedDegrees)),
        state,
        52.0,
        5.0,
        hasLastKnown,
        accumulated,
        lastEvidenceTime,
        now
    )

    private fun observeActivity(activityType: Int, state: MovementState, lastEvidenceTime: Long = 0L) =
        controller.observe(
            MovementEvidence.Activity(activityType, now),
            state,
            52.0,
            5.0,
            true,
            0f,
            lastEvidenceTime,
            now
        )

    // --- Fix evidence ---

    @Test
    fun `stationary goes moving when the accumulated distance exceeds the threshold`() {
        val decision = observeFix(MovementState.STATIONARY, movedDegrees = 0.001)
        assertTrue(decision is MovementDecision.ToMoving)
    }

    @Test
    fun `stationary stays when below the threshold`() {
        // 0.0002 degree is about 22 m, below the 100 m threshold
        val decision = observeFix(MovementState.STATIONARY, movedDegrees = 0.0002)
        assertEquals(MovementDecision.NoChange(), decision)
        assertFalse((decision as MovementDecision.NoChange).isEvidence)
    }

    @Test
    fun `accumulated distance triggers movement`() {
        val decision = observeFix(MovementState.STATIONARY, movedDegrees = 0.00054, accumulated = 50f)
        assertTrue(decision is MovementDecision.ToMoving)
    }

    @Test
    fun `moving stays moving and reports evidence for a large step`() {
        val decision = observeFix(MovementState.MOVING, movedDegrees = 0.001, lastEvidenceTime = now - 60_000)
        val noChange = decision as MovementDecision.NoChange
        assertTrue(noChange.isEvidence)
    }

    @Test
    fun `moving goes stationary when the burst expires`() {
        val decision = observeFix(
            MovementState.MOVING,
            movedDegrees = 0.0002,
            lastEvidenceTime = now - 11 * 60_000L
        )
        assertTrue(decision is MovementDecision.ToStationary)
    }

    @Test
    fun `moving stays moving while the burst is fresh`() {
        val decision = observeFix(
            MovementState.MOVING,
            movedDegrees = 0.0002,
            lastEvidenceTime = now - 60_000
        )
        assertEquals(MovementDecision.NoChange(), decision)
    }

    @Test
    fun `stale burst does not expire a stationary state`() {
        val decision = observeFix(
            MovementState.STATIONARY,
            movedDegrees = 0.0002,
            lastEvidenceTime = now - 11 * 60_000L
        )
        assertEquals(MovementDecision.NoChange(), decision)
    }

    @Test
    fun `fix without a previous fix never triggers movement`() {
        val decision = observeFix(MovementState.STATIONARY, movedDegrees = 1.0, hasLastKnown = false)
        assertEquals(MovementDecision.NoChange(), decision)
    }

    // --- Activity evidence ---

    @Test
    fun `moving activities switch a stationary state to moving`() {
        for (type in listOf(
            MovementStateController.ACTIVITY_TYPE_IN_VEHICLE,
            MovementStateController.ACTIVITY_TYPE_ON_FOOT,
            MovementStateController.ACTIVITY_TYPE_ON_BIKE,
            MovementStateController.ACTIVITY_TYPE_WALKING,
            MovementStateController.ACTIVITY_TYPE_RUNNING
        )) {
            val decision = observeActivity(type, MovementState.STATIONARY)
            assertTrue("type $type", decision is MovementDecision.ToMoving)
        }
    }

    @Test
    fun `still activity expires the burst`() {
        val decision = observeActivity(
            MovementStateController.ACTIVITY_TYPE_STILL,
            MovementState.MOVING,
            lastEvidenceTime = now - 11 * 60_000L
        )
        assertTrue(decision is MovementDecision.ToStationary)
    }

    @Test
    fun `still activity keeps a fresh burst alive`() {
        val decision = observeActivity(
            MovementStateController.ACTIVITY_TYPE_STILL,
            MovementState.MOVING,
            lastEvidenceTime = now - 60_000
        )
        assertEquals(MovementDecision.NoChange(), decision)
    }

    @Test
    fun `unknown activity is ignored`() {
        val decision = observeActivity(DetectedActivityType.UNKNOWN, MovementState.MOVING, now - 11 * 60_000L)
        val noChange = decision as MovementDecision.NoChange
        assertFalse(noChange.isEvidence)
    }

    // --- Wi-Fi evidence ---

    @Test
    fun `wifi loss switches a stationary state to moving`() {
        val decision = controller.observe(
            MovementEvidence.WifiLost,
            MovementState.STATIONARY,
            52.0, 5.0, true, 0f, 0L, now
        )
        assertTrue(decision is MovementDecision.ToMoving)
    }

    @Test
    fun `wifi loss while moving refreshes the burst`() {
        val decision = controller.observe(
            MovementEvidence.WifiLost,
            MovementState.MOVING,
            52.0, 5.0, true, 0f, now - 60_000, now
        )
        assertTrue((decision as MovementDecision.NoChange).isEvidence)
    }

    // --- Burst expiry (direct) ---

    @Test
    fun `burst expires exactly at the burst duration`() {
        val decision = controller.burstExpired(MovementState.MOVING, now - 10 * 60_000L, now)
        assertTrue(decision is MovementDecision.ToStationary)
    }

    @Test
    fun `burst does not expire before the burst duration`() {
        val decision = controller.burstExpired(MovementState.MOVING, now - 9 * 60_000L, now)
        assertEquals(MovementDecision.NoChange(), decision)
    }

    @Test
    fun `burst never expires a stationary state`() {
        val decision = controller.burstExpired(MovementState.STATIONARY, 0L, now)
        assertEquals(MovementDecision.NoChange(), decision)
    }

    // --- Accumulation ---

    @Test
    fun `segments below the jitter floor do not accumulate`() {
        val next = controller.nextAccumulatedMeters(52.0, 5.0, true, 10f, fix(movedDegrees = 0.00004))
        // 0.00004 degree is about 4.4 m, below the 5 m jitter floor
        assertEquals(10f, next, 0.001f)
    }

    @Test
    fun `segments above the jitter floor accumulate`() {
        val next = controller.nextAccumulatedMeters(52.0, 5.0, true, 10f, fix(movedDegrees = 0.001))
        assertEquals(10f + 111f, next, 1f)
    }

    @Test
    fun `no previous fix yields zero accumulation`() {
        val next = controller.nextAccumulatedMeters(52.0, 5.0, false, 10f, fix(movedDegrees = 0.001))
        assertEquals(0f, next, 0.001f)
    }

    // --- Activity type classification ---

    @Test
    fun `moving activity classification`() {
        assertTrue(controller.isMovingActivity(MovementStateController.ACTIVITY_TYPE_IN_VEHICLE))
        assertTrue(controller.isMovingActivity(MovementStateController.ACTIVITY_TYPE_RUNNING))
        assertFalse(controller.isMovingActivity(MovementStateController.ACTIVITY_TYPE_STILL))
        assertFalse(controller.isMovingActivity(8))
    }

    @Test
    fun `stationary activity classification`() {
        assertTrue(controller.isStationaryActivity(MovementStateController.ACTIVITY_TYPE_STILL))
        assertFalse(controller.isStationaryActivity(MovementStateController.ACTIVITY_TYPE_ON_FOOT))
    }

    private object DetectedActivityType {
        const val UNKNOWN = 8
    }
}
