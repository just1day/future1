package com.future1.safewalk

import android.content.Context
import com.future1.safewalk.core.SafeWalkConfig
import java.security.MessageDigest

/** 보호자 설정 저장소. PIN은 해시로만 저장한다. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("safewalk", Context.MODE_PRIVATE)

    var protectionOn: Boolean
        get() = sp.getBoolean("on", false)
        set(v) = sp.edit().putBoolean("on", v).apply()

    var lockWhileWaiting: Boolean
        get() = sp.getBoolean("lock_waiting", false)
        set(v) = sp.edit().putBoolean("lock_waiting", v).apply()

    /** 공공데이터포털 인증키. 앱에서 입력한 값이 없으면 빌드 때 넣은 값(local.properties)을 쓴다. */
    var serviceKey: String
        get() = sp.getString("service_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DATA_GO_KR_KEY
        set(v) = sp.edit().putString("service_key", v.trim()).apply()

    /** 공공 횡단보도 데이터를 받은 시각(ms, 0이면 아직 없음). */
    var publicUpdatedAt: Long
        get() = sp.getLong("public_updated_at", 0L)
        set(v) = sp.edit().putLong("public_updated_at", v).apply()

    /** 테스트용: 이 시각(elapsedRealtime)까지는 걷고 있다고 가정한다. */
    var simulateWalkingUntil: Long
        get() = sp.getLong("simulate_until", 0L)
        set(v) = sp.edit().putLong("simulate_until", v).apply()

    val hasPin: Boolean get() = sp.contains("pin")

    fun setPin(pin: String) = sp.edit().putString("pin", hash(pin)).apply()

    fun checkPin(pin: String): Boolean = !hasPin || sp.getString("pin", null) == hash(pin)

    fun config() = SafeWalkConfig(lockWhileWaitingAtCrosswalk = lockWhileWaiting)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("safewalk:" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
