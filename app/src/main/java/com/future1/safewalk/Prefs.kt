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

    val hasPin: Boolean get() = sp.contains("pin")

    fun setPin(pin: String) = sp.edit().putString("pin", hash(pin)).apply()

    fun checkPin(pin: String): Boolean = !hasPin || sp.getString("pin", null) == hash(pin)

    fun config() = SafeWalkConfig(lockWhileWaitingAtCrosswalk = lockWhileWaiting)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("safewalk:" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
