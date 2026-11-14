package com.future1.safewalk.core

data class SafeWalkConfig(
    /** 이 거리 안이면 "횡단보도 앞"으로 본다. */
    val crosswalkRadiusM: Double = 30.0,
    /** 일반 도로에서 걸으며 화면을 볼 때, 잠그기 전 잠깐 허용하는 시간. */
    val walkingGraceMs: Long = 5_000,
    /** 횡단보도 앞에서는 걷는 즉시 잠근다(0). */
    val crosswalkGraceMs: Long = 0,
    /** 멈춘 뒤 이 시간이 지나야 잠금이 풀린다. */
    val releaseHoldMs: Long = 3_000,
    /** 횡단보도 앞에서는 멈춰 서 있어도(신호 대기 중) 잠글지. GPS 정확도가 좋을 때만 적용. */
    val lockWhileWaitingAtCrosswalk: Boolean = false,
    /** 위 기능에 필요한 GPS 정확도(미터). */
    val waitingLockMaxAccuracyM: Double = 15.0,
)

enum class Level { SAFE, CAUTION, DANGER }

data class Snapshot(
    val nowMs: Long,
    val walking: Boolean,
    /** 화면이 켜져 있고 잠금 해제된 상태(=아이가 폰을 보고 있음). */
    val screenInUse: Boolean,
    val distanceToCrosswalkM: Double?,
    val locationAccuracyM: Double?,
)

data class Decision(
    val level: Level,
    val locked: Boolean,
    val message: String,
)

/**
 * 상태를 받아 "화면을 잠글지"를 결정한다. 안드로이드와 무관한 순수 로직이라 단위 테스트가 가능하다.
 */
class SafetyEngine(var config: SafeWalkConfig = SafeWalkConfig()) {
    private var riskySince: Long? = null
    private var safeSince: Long? = null
    private var locked = false

    fun evaluate(s: Snapshot): Decision {
        val nearCrosswalk = s.distanceToCrosswalkM != null && s.distanceToCrosswalkM <= config.crosswalkRadiusM
        val accurate = s.locationAccuracyM != null && s.locationAccuracyM <= config.waitingLockMaxAccuracyM
        val waitingAtCrosswalk = config.lockWhileWaitingAtCrosswalk && nearCrosswalk && accurate

        val level = when {
            nearCrosswalk && (s.walking || waitingAtCrosswalk) -> Level.DANGER
            s.walking -> Level.CAUTION
            else -> Level.SAFE
        }
        val risky = s.screenInUse && level != Level.SAFE

        if (risky) {
            safeSince = null
            val since = riskySince ?: s.nowMs.also { riskySince = it }
            val grace = if (level == Level.DANGER) config.crosswalkGraceMs else config.walkingGraceMs
            if (s.nowMs - since >= grace) locked = true
        } else {
            riskySince = null
            if (locked) {
                // 화면을 끈 경우는 바로 해제, 멈춘 경우는 잠시 기다렸다가 해제
                if (!s.screenInUse) {
                    locked = false
                } else {
                    val since = safeSince ?: s.nowMs.also { safeSince = it }
                    if (s.nowMs - since >= config.releaseHoldMs) {
                        locked = false
                        safeSince = null
                    }
                }
            }
        }
        return Decision(level, locked, messageFor(level, locked))
    }

    private fun messageFor(level: Level, locked: Boolean): String = when {
        !locked -> ""
        level == Level.DANGER -> "횡단보도 앞이에요!\n폰을 내리고 좌우를 살펴요."
        level == Level.CAUTION -> "걸을 때는 앞을 봐요!\n멈추면 다시 쓸 수 있어요."
        else -> "잠깐만요, 멈췄는지 확인 중이에요…"
    }
}
