package com.future1.safewalk.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 공공데이터포털 「전국횡단보도표준데이터」 Open API 연동.
 *
 * - 요청: https://api.data.go.kr/openapi/tn_pubr_public_crosswalk_api
 *         ?serviceKey=…&pageNo=1&numOfRows=1000&type=json
 * - 응답: response.header.resultCode("00"=정상, "03"=데이터 없음),
 *         response.body.totalCount, response.body.items[] { latitude, longitude, crslkKnd, ctprvnNm, signguNm … }
 *
 * 지역 필터 파라미터가 데이터셋마다 달라 믿기 어렵기 때문에, 전국 자료를 한 번 받아서 휴대폰에 저장해 쓴다.
 */
object PublicCrosswalkApi {
    const val ENDPOINT = "https://api.data.go.kr/openapi/tn_pubr_public_crosswalk_api"
    const val PAGE_SIZE = 1000

    /**
     * 공공데이터포털은 "Encoding 키"(이미 %인코딩됨)와 "Decoding 키"를 함께 준다.
     * 어느 쪽을 붙여넣어도 동작하도록, %가 들어 있으면 그대로 쓰고 아니면 인코딩한다.
     */
    fun pageUrl(serviceKey: String, pageNo: Int, numOfRows: Int = PAGE_SIZE): String {
        val key = serviceKey.trim()
        val encodedKey = if ('%' in key) key else URLEncoder.encode(key, "UTF-8")
        return "$ENDPOINT?serviceKey=$encodedKey&pageNo=$pageNo&numOfRows=$numOfRows&type=json"
    }
}

class PublicDataException(message: String) : Exception(message)

data class CrosswalkPage(val totalCount: Int, val points: List<GeoPoint>, val rawItemCount: Int)

object PublicCrosswalkParser {
    private val XML_MSG = Regex("<(returnAuthMsg|resultMsg|errMsg)>([^<]*)</")

    fun parse(body: String): CrosswalkPage {
        val text = body.trim()
        if (text.startsWith("<")) {
            // 인증키 오류 등은 type=json이어도 XML로 온다
            val msg = XML_MSG.findAll(text).map { it.groupValues[2] }.filter { it.isNotBlank() }.toList()
            throw PublicDataException(explain(msg.lastOrNull() ?: "알 수 없는 XML 응답"))
        }
        val response = JSONObject(text).optJSONObject("response")
            ?: throw PublicDataException("응답 형식이 예상과 달라요")
        val header = response.optJSONObject("header")
        val code = header?.optString("resultCode").orEmpty()
        if (code == "03") return CrosswalkPage(0, emptyList(), 0)
        if (code.isNotEmpty() && code != "00") {
            throw PublicDataException(explain("$code ${header?.optString("resultMsg").orEmpty()}".trim()))
        }
        val bodyObj = response.optJSONObject("body") ?: return CrosswalkPage(0, emptyList(), 0)
        val total = bodyObj.optString("totalCount").toIntOrNull() ?: 0
        val items = itemsArray(bodyObj)
        val points = ArrayList<GeoPoint>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val lat = number(item, "latitude", "위도") ?: continue
            val lon = number(item, "longitude", "경도") ?: continue
            val p = GeoPoint(lat, lon)
            if (Geo.isInKorea(p)) points += p
        }
        return CrosswalkPage(total, points, items.length())
    }

    /** items가 배열로 오기도 하고 {"item": [...]} 또는 {"item": {...}}로 오기도 한다. */
    private fun itemsArray(body: JSONObject): JSONArray {
        body.optJSONArray("items")?.let { return it }
        val obj = body.optJSONObject("items") ?: return JSONArray()
        obj.optJSONArray("item")?.let { return it }
        obj.optJSONObject("item")?.let { return JSONArray().put(it) }
        return JSONArray()
    }

    private fun number(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            o.optString(k).trim().toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun explain(raw: String): String = when {
        "SERVICE_KEY_IS_NOT_REGISTERED" in raw || raw.startsWith("30") ->
            "인증키가 등록되지 않았어요. 활용신청 승인 후 1~2시간 뒤에 다시 해 보세요. ($raw)"
        "LIMITED_NUMBER_OF_SERVICE_REQUESTS" in raw || raw.startsWith("22") ->
            "오늘 호출 한도를 넘었어요. 내일 다시 받아 주세요. ($raw)"
        "SERVICE_ACCESS_DENIED" in raw || raw.startsWith("20") ->
            "이 API에 대한 활용신청이 필요해요. ($raw)"
        else -> "공공데이터 오류: $raw"
    }
}

/**
 * 모든 페이지를 차례로 받아 횡단보도 좌표를 모은다.
 * HTTP 호출은 [fetch]로 주입받아(안드로이드/테스트에서 각각 구현) 이 클래스는 순수 로직만 가진다.
 */
class PublicCrosswalkDownloader(
    private val fetch: (url: String) -> String,
    private val pageSize: Int = PublicCrosswalkApi.PAGE_SIZE,
    private val retriesPerPage: Int = 3,
    private val maxPages: Int = 2_000,
) {
    fun downloadAll(serviceKey: String, onProgress: (fetchedRows: Int, totalRows: Int) -> Unit = { _, _ -> }): List<GeoPoint> {
        require(serviceKey.isNotBlank()) { "인증키가 비어 있어요" }
        val all = ArrayList<GeoPoint>()
        var fetchedRows = 0
        var total = Int.MAX_VALUE
        var page = 1
        while (fetchedRows < total && page <= maxPages) {
            val result = fetchPage(serviceKey, page)
            if (page == 1) total = result.totalCount
            if (result.rawItemCount == 0) break
            fetchedRows += result.rawItemCount
            all += result.points
            onProgress(fetchedRows, total)
            page++
        }
        return all
    }

    private fun fetchPage(key: String, page: Int): CrosswalkPage {
        var last: Exception? = null
        repeat(retriesPerPage) {
            try {
                return PublicCrosswalkParser.parse(fetch(PublicCrosswalkApi.pageUrl(key, page, pageSize)))
            } catch (e: PublicDataException) {
                throw e // 인증키 오류 같은 건 다시 해도 안 된다
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("page $page failed")
    }
}

/** 받은 좌표를 "위도,경도" 줄 단위 텍스트로 저장/읽기. 전국 데이터도 수 MB 이내. */
object CrosswalkCache {
    fun serialize(points: List<GeoPoint>): String = buildString(points.size * 22) {
        for (p in points) append(String.format(java.util.Locale.US, "%.6f,%.6f\n", p.lat, p.lon))
    }

    fun parse(text: String): List<GeoPoint> = text.lineSequence().mapNotNull { line ->
        val comma = line.indexOf(',')
        if (comma <= 0) return@mapNotNull null
        val lat = line.substring(0, comma).toDoubleOrNull() ?: return@mapNotNull null
        val lon = line.substring(comma + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
        GeoPoint(lat, lon)
    }.toList()
}
