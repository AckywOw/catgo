package com.catgo

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.*

internal data class Point(val lat: Double, val lon: Double) {
    init { require(lat in -90.0..90.0 && lon in -180.0..180.0) { "坐标超出有效范围" } }

    fun gcj(): Point {
        if (!(lon > 73.66 && lon < 135.05 && lat > 3.86 && lat < 53.55)) return this
        val x = lon - 105
        val y = lat - 35
        val waves = (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        val a = -100 + 2*x + 3*y + .2*y*y + .1*x*y + .2*sqrt(abs(x)) + waves +
            (20*sin(y*PI) + 40*sin(y/3*PI) + 160*sin(y/12*PI) + 320*sin(y*PI/30))*2/3
        val b = 300 + x + 2*y + .1*x*x + .1*x*y + .1*sqrt(abs(x)) + waves +
            (20*sin(x*PI) + 40*sin(x/3*PI) + 150*sin(x/12*PI) + 300*sin(x/30*PI))*2/3
        val rad = lat * PI / 180
        val magic = 1 - .00669342162296594323 * sin(rad).pow(2)
        return Point(lat + a * 180 / (6378245 * (1 - .00669342162296594323) / (magic * sqrt(magic)) * PI),
            lon + b * 180 / (6378245 / sqrt(magic) * cos(rad) * PI))
    }

    fun wgs(): Point {
        var current = this
        repeat(8) {
            val shifted = current.gcj()
            current = Point(current.lat + lat - shifted.lat, current.lon + lon - shifted.lon)
        }
        return current
    }

    companion object {
        fun parse(query: String): Point? {
            val expression = Regex("""(?i)(?:(wgs(?:84)?|gcj|高德)[:：]\s*)?([+-]?\d+(?:\.\d+)?)\s*[,，;；\s]\s*([+-]?\d+(?:\.\d+)?)""")
            val match = expression.matchEntire(query.trim()) ?: return null
            val point = Point(match.groupValues[2].toDouble(), match.groupValues[3].toDouble())
            return if (match.groupValues[1].lowercase() in setOf("gcj", "高德")) point.wgs() else point
        }
    }
}

internal data class Place(val name: String, val address: String, val point: Point)

internal object MapSearch {
    private fun json(host: String, path: String, params: Map<String, String>): JSONObject {
        val uri = Uri.Builder().scheme("https").authority(host).path(path).apply {
            params.forEach { (key, value) -> appendQueryParameter(key, value) }
        }.build()
        return request(uri.toString()) { JSONObject(it.bufferedReader().readText()) }
    }

    fun search(query: String, settings: Map<String, Any>, center: Point): List<Place> {
        require(query.length <= 200) { "搜索内容请控制在 200 字以内" }
        Point.parse(query)?.let { return listOf(Place("坐标", "WGS-84", it)) }
        val city = settings["city"] as? String ?: ""
        val key = settings["key"] as? String ?: ""
        val provider = settings["provider"] as? String ?: "auto"
        val nearby = settings["nearby"] == true
        if (provider != "photon" && key.isNotEmpty()) {
            val result = runCatching { amap(query, key, city, center.takeIf { nearby }) }
            if (provider == "amap") return result.getOrThrow()
            result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val params = mutableMapOf("q" to listOf(city, query).filter { it.isNotBlank() }.joinToString(" "),
            "limit" to "12")
        if (nearby) {
            params["lat"] = center.lat.toString()
            params["lon"] = center.lon.toString()
        }
        val data = json("photon.komoot.io", "/api/", params)
        return data.optJSONArray("features").objects().mapNotNull { row ->
            val p = row.optJSONObject("properties") ?: return@mapNotNull null
            val coordinates = row.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val point = runCatching { Point(coordinates.optDouble(1), coordinates.optDouble(0)) }.getOrNull()
                ?: return@mapNotNull null
            val name = p.optString("name").ifBlank { p.optString("street").ifBlank { "地图地点" } }
            val address = listOf("state", "city", "district", "street", "housenumber")
                .map { p.optString(it) }.filter { it.isNotBlank() && it != name }.distinct().joinToString(" ")
            Place(name, address, point)
        }
    }

    private fun amap(query: String, key: String, city: String, center: Point?): List<Place> {
        fun request(path: String, parameters: Map<String, String>): JSONObject {
            val response = json("restapi.amap.com", path, parameters + ("key" to key))
            check(response.optString("status") == "1") { "高德搜索失败，请检查 Web 服务 Key 与配额" }
            return response
        }
        fun parse(rows: JSONArray?, fallbackName: String = query): List<Place> = rows.objects().mapNotNull { row ->
            val values = row.optString("location").split(',')
            if (values.size != 2) return@mapNotNull null
            val point = runCatching { Point(values[1].toDouble(), values[0].toDouble()).wgs() }.getOrNull()
                ?: return@mapNotNull null
            val name = (row.opt("name") as? String).orEmpty().ifBlank { fallbackName }
            val address = listOf("district", "address", "formatted_address").mapNotNull { row.opt(it) as? String }
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
            Place(name, address, point)
        }
        val params = buildMap {
            put("keywords", query)
            if (city.isNotBlank()) put("city", city)
        }
        val tips = request("/v3/assistant/inputtips", params + if (center != null) {
            val point = center.gcj()
            mapOf("location" to "${point.lon},${point.lat}")
        } else emptyMap()).optJSONArray("tips")
        val direct = parse(tips)
        if (direct.isNotEmpty()) return direct
        if (center != null) {
            val point = center.gcj()
            val nearby = parse(request("/v3/place/around", params + mapOf(
                "location" to "${point.lon},${point.lat}", "radius" to "50000", "offset" to "12"
            )).optJSONArray("pois"))
            if (nearby.isNotEmpty()) return nearby
        }
        val pois = parse(request("/v3/place/text", params + ("offset" to "12")).optJSONArray("pois"))
        if (pois.isNotEmpty()) return pois
        val detail = tips.objects().mapNotNull { it.optString("id").takeIf(String::isNotBlank) }.firstOrNull()
        if (detail != null) {
            val found = parse(request("/v3/place/detail", mapOf("id" to detail)).optJSONArray("pois"))
            if (found.isNotEmpty()) return found
        }
        return parse(request("/v3/geocode/geo", params - "keywords" + ("address" to query)).optJSONArray("geocodes"))
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun <T> request(address: String, read: (java.io.InputStream) -> T): T {
    val connection = URL(address).openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "catgo/1.0 Android")
        check(connection.responseCode == 200) { "地图服务暂不可用（HTTP ${connection.responseCode}）" }
        return connection.inputStream.use(read)
    } finally {
        connection.disconnect()
    }
}
