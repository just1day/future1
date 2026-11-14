package com.future1.safewalk.core

import kotlin.math.asin
import kotlin.math.cos
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
}

/** 주변 횡단보도 좌표 목록. 가장 가까운 횡단보도까지의 거리를 구한다. */
class CrosswalkIndex(val crossings: List<GeoPoint>) {
    fun nearestDistanceMeters(p: GeoPoint): Double? =
        crossings.minOfOrNull { Geo.distanceMeters(p, it) }

    companion object {
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
