package com.catgo

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
