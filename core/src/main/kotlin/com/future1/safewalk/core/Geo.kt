package com.future1.safewalk.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 두 좌표 사이의 거리(미터), haversine 공식. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** 대한민국 영역 안의 좌표인지(잘못 입력된 공공데이터 걸러내기용). */
    fun isInKorea(p: GeoPoint) = p.lat in 33.0..39.0 && p.lon in 124.0..132.0
}

data class Nearest(val point: GeoPoint, val distanceM: Double)

/**
 * 횡단보도 좌표 검색용 격자 인덱스.
 * 전국 수만~수십만 개를 넣어도 주변 칸(약 1km)만 보므로 0.5초마다 불러도 가볍다.
 * 검색 반경([searchCells] 칸) 밖의 횡단보도는 "없음(null)"으로 본다.
 */
class CrosswalkIndex(val crossings: List<GeoPoint>, private val searchCells: Int = 1) {
    private val grid: Map<Long, List<GeoPoint>> = crossings.groupBy { key(cell(it.lat), cell(it.lon)) }

    val size get() = crossings.size

    fun nearest(p: GeoPoint): Nearest? {
        val cy = cell(p.lat)
        val cx = cell(p.lon)
        var best: Nearest? = null
        for (dy in -searchCells..searchCells) for (dx in -searchCells..searchCells) {
            val bucket = grid[key(cy + dy, cx + dx)] ?: continue
            for (c in bucket) {
                val d = Geo.distanceMeters(p, c)
                if (best == null || d < best.distanceM) best = Nearest(c, d)
            }
        }
        return best
    }

    fun nearestDistanceMeters(p: GeoPoint): Double? = nearest(p)?.distanceM

    private fun cell(deg: Double) = floor(deg / CELL_DEG).toInt()
    private fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

    companion object {
        /** 0.01도 ≈ 위도 1.1km, 한국 경도 약 0.9km */
        private const val CELL_DEG = 0.01
        val EMPTY = CrosswalkIndex(emptyList())
    }
}

/** OpenStreetMap Overpass API로 반경 내 횡단보도(highway=crossing 등)를 조회하는 쿼리. */
object OverpassQuery {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    fun build(center: GeoPoint, radiusMeters: Int): String {
        val around = "around:$radiusMeters,${center.lat},${center.lon}"
        return "[out:json][timeout:15];(" +
            "node[\"highway\"=\"crossing\"]($around);" +
            "node[\"crossing\"]($around);" +
            "node[\"highway\"=\"traffic_signals\"]($around);" +
            ");out;"
    }
}
