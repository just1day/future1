package com.future1.safewalk

import android.os.SystemClock
import android.util.Log
import com.future1.safewalk.core.CrosswalkIndex
import com.future1.safewalk.core.Geo
import com.future1.safewalk.core.GeoPoint
import com.future1.safewalk.core.OverpassQuery
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 현재 위치 주변 횡단보도를 OpenStreetMap에서 받아와 메모리에 들고 있는다.
 * 일정 거리 이상 이동했을 때만 다시 받아서 데이터·배터리를 아낀다.
 */
class CrosswalkRepository(
    private val radiusM: Int = 600,
    private val refetchAfterMovedM: Double = 250.0,
    private val minRetryMs: Long = 30_000,
) {
    @Volatile var index: CrosswalkIndex = CrosswalkIndex.EMPTY
        private set

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var fetching = false
    private var lastCenter: GeoPoint? = null
    private var lastAttemptAt = 0L

    fun maybeRefresh(here: GeoPoint) {
        if (fetching) return
        val now = SystemClock.elapsedRealtime()
        val center = lastCenter
        val moved = center == null || Geo.distanceMeters(center, here) > refetchAfterMovedM
        if (!moved || now - lastAttemptAt < minRetryMs && lastAttemptAt != 0L) return

        fetching = true
        lastAttemptAt = now
        executor.execute {
            try {
                index = CrosswalkIndex(fetch(here))
                lastCenter = here
                Log.i(TAG, "crossings loaded: ${index.crossings.size}")
            } catch (e: Exception) {
                Log.w(TAG, "crosswalk fetch failed", e)
            } finally {
                fetching = false
            }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun fetch(center: GeoPoint): List<GeoPoint> {
        val body = "data=" + URLEncoder.encode(OverpassQuery.build(center, radiusM), "UTF-8")
        val conn = URI(OverpassQuery.ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "SafeWalk/0.1 (child pedestrian safety)")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val elements = json.getJSONArray("elements")
            return (0 until elements.length()).mapNotNull { i ->
                val el = elements.getJSONObject(i)
                if (el.has("lat") && el.has("lon")) GeoPoint(el.getDouble("lat"), el.getDouble("lon")) else null
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object { private const val TAG = "CrosswalkRepo" }
}
