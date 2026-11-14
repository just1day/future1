package com.future1.safewalk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.future1.safewalk.core.CrosswalkCache
import com.future1.safewalk.core.CrosswalkIndex
import com.future1.safewalk.core.Geo
import com.future1.safewalk.core.GeoPoint
import com.future1.safewalk.core.Nearest
import com.future1.safewalk.core.OverpassQuery
import com.future1.safewalk.core.PublicCrosswalkDownloader
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors

enum class CrosswalkSource(val label: String) { PUBLIC("공공데이터"), OSM("OpenStreetMap") }

data class NearestCrosswalk(val nearest: Nearest, val source: CrosswalkSource)

/**
 * 횡단보도 위치 저장소 (앱 전체에서 하나).
 *
 * 1. 공공데이터 「전국횡단보도표준데이터」: 보호자가 한 번 받아 두면 파일로 저장해 계속 사용 (주 데이터)
 * 2. OpenStreetMap: 공공데이터에 빠진 곳을 보완 (현재 위치 주변 600m만 수시로 받음)
 *
 * 두 데이터 중 더 가까운 횡단보도를 사용한다.
 */
class CrosswalkRepository private constructor(private val appContext: Context) {
    @Volatile var publicIndex: CrosswalkIndex = CrosswalkIndex.EMPTY
        private set
    @Volatile var osmIndex: CrosswalkIndex = CrosswalkIndex.EMPTY
        private set
    @Volatile var downloading = false
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val osmIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cacheFile = File(appContext.filesDir, "crosswalks_public.csv")

    @Volatile private var osmFetching = false
    private var osmCenter: GeoPoint? = null
    private var osmLastAttemptAt = 0L

    init {
        io.execute { loadPublicCache() }
    }

    fun nearest(p: GeoPoint): NearestCrosswalk? {
        val pub = publicIndex.nearest(p)?.let { NearestCrosswalk(it, CrosswalkSource.PUBLIC) }
        val osm = osmIndex.nearest(p)?.let { NearestCrosswalk(it, CrosswalkSource.OSM) }
        return listOfNotNull(pub, osm).minByOrNull { it.nearest.distanceM }
    }

    // ---- 공공데이터 ----

    private fun loadPublicCache() {
        if (!cacheFile.exists()) return
        try {
            publicIndex = CrosswalkIndex(CrosswalkCache.parse(cacheFile.readText()))
            Log.i(TAG, "public crosswalks loaded from cache: ${publicIndex.size}")
        } catch (e: Exception) {
            Log.w(TAG, "cache read failed", e)
        }
    }

    /**
     * 전국 횡단보도 데이터를 받아 저장한다. 콜백은 메인 스레드에서 불린다.
     * @param onProgress (받은 행, 전체 행)
     * @param onDone 성공 시 저장된 좌표 개수, 실패 시 오류
     */
    fun downloadPublic(
        serviceKey: String,
        onProgress: (Int, Int) -> Unit,
        onDone: (Result<Int>) -> Unit,
    ) {
        if (downloading) return
        downloading = true
        io.execute {
            val result = runCatching {
                val points = PublicCrosswalkDownloader(fetch = ::httpGet).downloadAll(serviceKey) { f, t ->
                    main.post { onProgress(f, t) }
                }
                if (points.isEmpty()) error("받은 횡단보도가 0개예요. 인증키와 활용신청 상태를 확인해 주세요.")
                val tmp = File(cacheFile.path + ".tmp")
                tmp.writeText(CrosswalkCache.serialize(points))
                if (!tmp.renameTo(cacheFile)) error("파일 저장 실패")
                publicIndex = CrosswalkIndex(points)
                Prefs(appContext).publicUpdatedAt = System.currentTimeMillis()
                points.size
            }
            downloading = false
            result.exceptionOrNull()?.let { Log.w(TAG, "public download failed", it) }
            main.post { onDone(result) }
        }
    }

    private fun httpGet(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            // 공공데이터 오류는 200이 아닌 코드로도 XML 본문을 주므로 본문이 있으면 파서에 넘긴다
            if (code !in 200..299 && !text.trimStart().startsWith("<")) error("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ---- OpenStreetMap (보완) ----

    fun maybeRefreshOsm(here: GeoPoint) {
        if (osmFetching) return
        val now = SystemClock.elapsedRealtime()
        val center = osmCenter
        val moved = center == null || Geo.distanceMeters(center, here) > OSM_REFETCH_MOVED_M
        if (!moved || (osmLastAttemptAt != 0L && now - osmLastAttemptAt < OSM_MIN_RETRY_MS)) return

        osmFetching = true
        osmLastAttemptAt = now
        osmIo.execute {
            try {
                osmIndex = CrosswalkIndex(fetchOsm(here))
                osmCenter = here
            } catch (e: Exception) {
                Log.w(TAG, "OSM fetch failed", e)
            } finally {
                osmFetching = false
            }
        }
    }

    private fun fetchOsm(center: GeoPoint): List<GeoPoint> {
        val body = "data=" + URLEncoder.encode(OverpassQuery.build(center, OSM_RADIUS_M), "UTF-8")
        val conn = URI(OverpassQuery.ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "SafeWalk/0.2 (child pedestrian safety)")
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

    companion object {
        private const val TAG = "CrosswalkRepo"
        private const val OSM_RADIUS_M = 600
        private const val OSM_REFETCH_MOVED_M = 250.0
        private const val OSM_MIN_RETRY_MS = 30_000L

        @Volatile private var instance: CrosswalkRepository? = null

        fun get(context: Context): CrosswalkRepository =
            instance ?: synchronized(this) {
                instance ?: CrosswalkRepository(context.applicationContext).also { instance = it }
            }
    }
}
