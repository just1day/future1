package com.future1.safewalk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 폰을 껐다 켜거나 앱이 업데이트돼도 보호가 다시 켜지게 한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).protectionOn) return
        try {
            SafeWalkService.start(context)
        } catch (e: Exception) {
            // Android 14+는 '항상 허용' 위치 권한이 없으면 부팅 시 위치 서비스를 시작할 수 없다
            Log.w("BootReceiver", "could not restart protection", e)
        }
    }
}
