package com.example.shift.utils

object PolylineUtils {

    fun encodePolyline(points: List<Pair<Double, Double>>): String {
        val result = StringBuilder()
        var prevLat = 0
        var prevLng = 0

        for (point in points) {
            val lat = Math.round(point.first * 1E5).toInt()
            val lng = Math.round(point.second * 1E5).toInt()

            val dLat = lat - prevLat
            val dLng = lng - prevLng

            encode(dLat, result)
            encode(dLng, result)

            prevLat = lat
            prevLng = lng
        }
        return result.toString()
    }

    private fun encode(v: Int, result: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else v shl 1
        while (value >= 0x20) {
            result.append((((value and 0x1f) or 0x20) + 63).toChar())
            value = value shr 5
        }
        result.append((value + 63).toChar())
    }

    fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val poly = mutableListOf<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val pLat = lat.toDouble() / 1E5
            val pLng = lng.toDouble() / 1E5
            poly.add(Pair(pLat, pLng))
        }

        return poly
    }

    fun distanceToSegment(lat1: Double, lon1: Double, lat2: Double, lon2: Double, ptLat: Double, ptLon: Double): Double {
        val R = 6371e3
        
        val p1Lat = Math.toRadians(lat1)
        val p1Lon = Math.toRadians(lon1)
        val p2Lat = Math.toRadians(lat2)
        val p2Lon = Math.toRadians(lon2)
        val p3Lat = Math.toRadians(ptLat)
        val p3Lon = Math.toRadians(ptLon)

        val x2 = (p2Lon - p1Lon) * Math.cos(p1Lat) * R
        val y2 = (p2Lat - p1Lat) * R

        val x3 = (p3Lon - p1Lon) * Math.cos(p1Lat) * R
        val y3 = (p3Lat - p1Lat) * R

        val l2 = x2*x2 + y2*y2
        if (l2 == 0.0) return Math.sqrt(x3*x3 + y3*y3)

        val t = Math.max(0.0, Math.min(1.0, (x3 * x2 + y3 * y2) / l2))

        val projX = t * x2
        val projY = t * y2

        val dx = x3 - projX
        val dy = y3 - projY

        return Math.sqrt(dx*dx + dy*dy)
    }

    fun distanceToPolyline(polyline: List<Pair<Double, Double>>, ptLat: Double, ptLon: Double): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) {
            val p = polyline[0]
            return distanceToSegment(p.first, p.second, p.first, p.second, ptLat, ptLon)
        }
        var minDistance = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val p1 = polyline[i]
            val p2 = polyline[i+1]
            val dist = distanceToSegment(p1.first, p1.second, p2.first, p2.second, ptLat, ptLon)
            if (dist < minDistance) {
                minDistance = dist
            }
        }
        return minDistance
    }
}
