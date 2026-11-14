package com.future1.safewalk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun distanceIsRoughlyCorrect() {
        // 위도 0.001도 ≈ 111m
        val d = Geo.distanceMeters(GeoPoint(37.5665, 126.9780), GeoPoint(37.5675, 126.9780))
        assertTrue(d in 110.0..112.5, "was $d")
    }

    @Test
    fun crosswalkIndexFindsNearest() {
        val here = GeoPoint(37.5665, 126.9780)
        val idx = CrosswalkIndex(listOf(GeoPoint(37.5675, 126.9780), GeoPoint(37.5666, 126.9780)))
        assertTrue(idx.nearestDistanceMeters(here)!! < 15.0)
        assertNull(CrosswalkIndex.EMPTY.nearestDistanceMeters(here))
    }

    @Test
    fun overpassQueryContainsCenter() {
        val q = OverpassQuery.build(GeoPoint(37.5, 127.0), 500)
        assertTrue("around:500,37.5,127.0" in q)
        assertTrue("highway\"=\"crossing" in q)
    }
}

class WalkingDetectorTest {
    @Test
    fun stepsMeanWalking() {
        val d = WalkingDetector()
        for (i in 0 until 6) d.onStep(i * 500L)
        assertTrue(d.isWalking(3_000))
    }

    @Test
    fun fewStepsAreNotWalking() {
        val d = WalkingDetector()
        d.onStep(0); d.onStep(500)
        assertFalse(d.isWalking(600))
    }

    @Test
    fun stopsAfterHoldTime() {
        val d = WalkingDetector()
        for (i in 0 until 6) d.onStep(i * 500L)
        assertTrue(d.isWalking(2_600))
        assertTrue(d.isWalking(5_000))   // 아직 stopHold 안
        assertFalse(d.isWalking(9_000))  // 걸음 끊긴 지 충분히 지남
    }

    @Test
    fun gpsSpeedMeansWalking() {
        val d = WalkingDetector()
        d.onLocation(1_000, 1.3, 8.0)
        assertTrue(d.isWalking(1_500))
    }

    @Test
    fun inaccurateGpsIsIgnored() {
        val d = WalkingDetector()
        d.onLocation(1_000, 1.3, 80.0)
        assertFalse(d.isWalking(1_500))
    }

    @Test
    fun vehicleSpeedWithoutStepsIsNotWalking() {
        val d = WalkingDetector()
        d.onLocation(1_000, 12.0, 5.0)
        assertFalse(d.isWalking(1_500))
    }
}

class SafetyEngineTest {
    private fun snap(t: Long, walking: Boolean, screen: Boolean = true, dist: Double? = null, acc: Double? = 5.0) =
        Snapshot(t, walking, screen, dist, acc)

    @Test
    fun notWalkingIsSafe() {
        val e = SafetyEngine()
        val r = e.evaluate(snap(0, walking = false))
        assertEquals(Level.SAFE, r.level)
        assertFalse(r.locked)
    }

    @Test
    fun walkingLocksAfterGrace() {
        val e = SafetyEngine()
        assertFalse(e.evaluate(snap(0, true)).locked)
        assertFalse(e.evaluate(snap(4_000, true)).locked)
        val r = e.evaluate(snap(5_000, true))
        assertTrue(r.locked)
        assertEquals(Level.CAUTION, r.level)
    }

    @Test
    fun crosswalkLocksImmediately() {
        val e = SafetyEngine()
        val r = e.evaluate(snap(0, true, dist = 10.0))
        assertTrue(r.locked)
        assertEquals(Level.DANGER, r.level)
        assertTrue("횡단보도" in r.message)
    }

    @Test
    fun walkingWithScreenOffNeverLocks() {
        val e = SafetyEngine()
        for (t in 0L..20_000L step 1_000) assertFalse(e.evaluate(snap(t, true, screen = false, dist = 5.0)).locked)
    }

    @Test
    fun releasesAfterStoppingForHoldTime() {
        val e = SafetyEngine()
        assertTrue(e.evaluate(snap(0, true, dist = 5.0)).locked)
        assertTrue(e.evaluate(snap(1_000, false, dist = 5.0)).locked)
        assertTrue(e.evaluate(snap(3_000, false, dist = 5.0)).locked)
        assertFalse(e.evaluate(snap(4_000, false, dist = 5.0)).locked)
    }

    @Test
    fun walkingAgainResetsRelease() {
        val e = SafetyEngine()
        e.evaluate(snap(0, true, dist = 5.0))
        e.evaluate(snap(1_000, false))
        e.evaluate(snap(2_000, true, dist = 5.0))   // 다시 걷기 시작
        assertTrue(e.evaluate(snap(4_500, false)).locked)   // 멈춤 카운트 다시 시작
        assertTrue(e.evaluate(snap(6_500, false)).locked)
        assertFalse(e.evaluate(snap(7_500, false)).locked)
    }

    @Test
    fun screenOffReleasesImmediately() {
        val e = SafetyEngine()
        assertTrue(e.evaluate(snap(0, true, dist = 5.0)).locked)
        assertFalse(e.evaluate(snap(100, true, screen = false, dist = 5.0)).locked)
    }

    @Test
    fun waitingAtCrosswalkLocksOnlyWhenEnabledAndAccurate() {
        val off = SafetyEngine()
        assertFalse(off.evaluate(snap(0, false, dist = 5.0)).locked)

        val on = SafetyEngine(SafeWalkConfig(lockWhileWaitingAtCrosswalk = true))
        assertTrue(on.evaluate(snap(0, false, dist = 5.0, acc = 8.0)).locked)

        val inaccurate = SafetyEngine(SafeWalkConfig(lockWhileWaitingAtCrosswalk = true))
        assertFalse(inaccurate.evaluate(snap(0, false, dist = 5.0, acc = 40.0)).locked)
    }
}
