package com.future1.safewalk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun item(lat: Any?, lon: Any?) =
    """{"ctprvnNm":"서울특별시","signguNm":"중구","crslkKnd":"일반횡단보도","latitude":${lat?.let { "\"$it\"" } ?: "\"\""},"longitude":${lon?.let { "\"$it\"" } ?: "\"\""}}"""

private fun page(total: Int, vararg items: String, code: String = "00") =
    """{"response":{"header":{"resultCode":"$code","resultMsg":"NORMAL SERVICE."},
       "body":{"items":[${items.joinToString(",")}],"totalCount":"$total","numOfRows":"1000","pageNo":"1"}}}"""

class PublicCrosswalkParserTest {
    @Test
    fun parsesItemsArrayWithStringNumbers() {
        val r = PublicCrosswalkParser.parse(page(2, item("37.5665", "126.9780"), item("35.1796", "129.0756")))
        assertEquals(2, r.totalCount)
        assertEquals(2, r.points.size)
        assertEquals(GeoPoint(37.5665, 126.9780), r.points[0])
    }

    @Test
    fun parsesItemWrappedObject() {
        val body = """{"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,
            "items":{"item":{"latitude":37.5,"longitude":127.0}}}}}"""
        assertEquals(listOf(GeoPoint(37.5, 127.0)), PublicCrosswalkParser.parse(body).points)
    }

    @Test
    fun skipsEmptyAndOutOfKoreaCoordinates() {
        val r = PublicCrosswalkParser.parse(page(3, item(null, null), item("0", "0"), item("37.5", "127.0")))
        assertEquals(listOf(GeoPoint(37.5, 127.0)), r.points)
        assertEquals(3, r.rawItemCount)   // 페이지 계산에는 원래 개수를 쓴다
    }

    @Test
    fun noDataCodeIsEmpty() {
        val r = PublicCrosswalkParser.parse(page(0, code = "03"))
        assertTrue(r.points.isEmpty())
    }

    @Test
    fun xmlKeyErrorIsExplained() {
        val xml = """<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>
            <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode>
            </cmmMsgHeader></OpenAPI_ServiceResponse>"""
        val e = assertFailsWith<PublicDataException> { PublicCrosswalkParser.parse(xml) }
        assertTrue("인증키" in e.message!!)
    }

    @Test
    fun jsonErrorCodeThrows() {
        val e = assertFailsWith<PublicDataException> { PublicCrosswalkParser.parse(page(0, code = "22")) }
        assertTrue("한도" in e.message!!)
    }
}

class PublicCrosswalkApiTest {
    @Test
    fun decodingKeyGetsEncoded() {
        val url = PublicCrosswalkApi.pageUrl("ab+c/d==", 3)
        assertTrue("serviceKey=ab%2Bc%2Fd%3D%3D&pageNo=3&numOfRows=1000&type=json" in url, url)
    }

    @Test
    fun encodingKeyIsKeptAsIs() {
        val url = PublicCrosswalkApi.pageUrl(" ab%2Bc%3D%3D ", 1)
        assertTrue("serviceKey=ab%2Bc%3D%3D&" in url, url)
    }
}

class PublicCrosswalkDownloaderTest {
    @Test
    fun pagesUntilTotalCount() {
        val requested = mutableListOf<String>()
        val pages = mapOf(
            1 to page(5, item("37.1", "127.1"), item("37.2", "127.2")),
            2 to page(5, item("37.3", "127.3"), item("37.4", "127.4")),
            3 to page(5, item("37.5", "127.5")),
        )
        val d = PublicCrosswalkDownloader(fetch = { url ->
            requested += url
            pages.getValue(Regex("pageNo=(\\d+)").find(url)!!.groupValues[1].toInt())
        }, pageSize = 2)
        val progress = mutableListOf<Pair<Int, Int>>()
        val all = d.downloadAll("key") { f, t -> progress += f to t }
        assertEquals(5, all.size)
        assertEquals(3, requested.size)
        assertEquals(listOf(2 to 5, 4 to 5, 5 to 5), progress)
    }

    @Test
    fun retriesNetworkErrorsButNotKeyErrors() {
        var calls = 0
        val flaky = PublicCrosswalkDownloader(fetch = {
            calls++
            if (calls < 3) throw java.io.IOException("timeout") else page(1, item("37.1", "127.1"))
        })
        assertEquals(1, flaky.downloadAll("key").size)
        assertEquals(3, calls)

        var keyCalls = 0
        val badKey = PublicCrosswalkDownloader(fetch = {
            keyCalls++
            "<x><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></x>"
        })
        assertFailsWith<PublicDataException> { badKey.downloadAll("key") }
        assertEquals(1, keyCalls)
    }
}

class CrosswalkCacheAndIndexTest {
    @Test
    fun cacheRoundTrip() {
        val pts = listOf(GeoPoint(37.566535, 126.977969), GeoPoint(35.179554, 129.075642))
        assertEquals(pts, CrosswalkCache.parse(CrosswalkCache.serialize(pts)))
        assertTrue(CrosswalkCache.parse("garbage\n\n1,x\n").isEmpty())
    }

    @Test
    fun gridFindsNearestAcrossCellBoundary() {
        // 셀 경계(37.57) 양쪽에 점을 두고 경계 근처에서 검색
        val a = GeoPoint(37.5701, 126.9800)   // 위 칸, 약 22m
        val b = GeoPoint(37.5695, 126.9800)   // 아래 칸, 약 44m
        val idx = CrosswalkIndex(listOf(b, a))
        val n = assertNotNull(idx.nearest(GeoPoint(37.5699, 126.9800)))
        assertEquals(a, n.point)
        assertTrue(n.distanceM in 20.0..25.0)
    }

    @Test
    fun farAwayIsNull() {
        val idx = CrosswalkIndex(listOf(GeoPoint(35.1796, 129.0756)))   // 부산
        assertNull(idx.nearest(GeoPoint(37.5665, 126.9780)))            // 서울
    }

    @Test
    fun largeIndexIsFast() {
        val rnd = java.util.Random(1)
        val pts = List(100_000) { GeoPoint(37.4 + rnd.nextDouble() * 0.3, 126.8 + rnd.nextDouble() * 0.4) }
        val idx = CrosswalkIndex(pts)
        val start = System.nanoTime()
        repeat(1_000) { idx.nearest(GeoPoint(37.55, 127.0)) }
        val perQueryMs = (System.nanoTime() - start) / 1e6 / 1_000
        assertTrue(perQueryMs < 5.0, "query took $perQueryMs ms")
        assertFalse(idx.size == 0)
    }
}
