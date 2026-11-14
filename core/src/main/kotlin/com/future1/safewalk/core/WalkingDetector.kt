package com.future1.safewalk.core

/**
 * 걸음 센서(step detector)와 GPS 속도를 합쳐 "지금 걷고 있는지"를 판단한다.
 *
 * - 걸음: 최근 [stepWindowMs] 동안 [minStepsInWindow]걸음 이상이면 걷는 중.
 * - GPS: 정확도가 충분한 최근 위치의 속도가 걷기/뛰기 범위면 걷는 중.
 * - 차량(버스·자동차) 속도이고 걸음이 없으면 걷는 중이 아님.
 * - 멈춤 판정은 [stopHoldMs] 동안 걸음/이동이 없을 때만 내려서 깜빡임을 막는다.
 */
class WalkingDetector(
    private val stepWindowMs: Long = 4_000,
    private val minStepsInWindow: Int = 4,
    private val minWalkSpeedMps: Double = 0.6,
    private val vehicleSpeedMps: Double = 8.0,
    private val maxLocationAgeMs: Long = 5_000,
    private val maxAccuracyM: Double = 25.0,
    private val stopHoldMs: Long = 3_000,
) {
    private val steps = ArrayDeque<Long>()
    private var lastSpeed: Double? = null
    private var lastSpeedAt: Long = Long.MIN_VALUE
    private var lastMovingAt: Long = Long.MIN_VALUE

    fun onStep(timestampMs: Long) {
        steps.addLast(timestampMs)
        trim(timestampMs)
    }

    fun onLocation(timestampMs: Long, speedMps: Double?, accuracyM: Double?) {
        if (speedMps == null) return
        if (accuracyM != null && accuracyM > maxAccuracyM) return
        lastSpeed = speedMps
        lastSpeedAt = timestampMs
    }

    fun isWalking(nowMs: Long): Boolean {
        trim(nowMs)
        val speed = lastSpeed?.takeIf { nowMs - lastSpeedAt <= maxLocationAgeMs }
        val stepping = steps.size >= minStepsInWindow
        val inVehicle = speed != null && speed >= vehicleSpeedMps && !stepping
        val movingBySpeed = speed != null && speed >= minWalkSpeedMps && speed < vehicleSpeedMps

        if (!inVehicle && (stepping || movingBySpeed)) lastMovingAt = nowMs
        if (inVehicle) return false
        return lastMovingAt != Long.MIN_VALUE && nowMs - lastMovingAt <= stopHoldMs
    }

    private fun trim(nowMs: Long) {
        while (steps.isNotEmpty() && nowMs - steps.first() > stepWindowMs) steps.removeFirst()
    }
}
