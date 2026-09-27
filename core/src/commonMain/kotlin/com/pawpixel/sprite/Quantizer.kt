package com.pawpixel.sprite

import kotlin.random.Random

/** Deterministic k-means in OKLab. Same photo + settings = same sprite on every platform. */
object Quantizer {

    fun kMeans(points: List<Lab>, k: Int, seed: Int = 7, iterations: Int = 14): List<Lab> {
        if (points.isEmpty()) return emptyList()
        val distinct = points.distinct()
        if (distinct.size <= k) return distinct
        val rnd = Random(seed)

        // k-means++ initialisation.
        val centers = ArrayList<Lab>(k)
        centers += points[rnd.nextInt(points.size)]
        val d2 = DoubleArray(points.size) { Double.MAX_VALUE }
        while (centers.size < k) {
            val last = centers.last()
            var total = 0.0
            for (i in points.indices) {
                val d = points[i].dist2(last)
                if (d < d2[i]) d2[i] = d
                total += d2[i]
            }
            if (total <= 0.0) break
            var target = rnd.nextDouble() * total
            var pick = points.size - 1
            for (i in points.indices) { target -= d2[i]; if (target <= 0) { pick = i; break } }
            centers += points[pick]
        }

        val assign = IntArray(points.size)
        repeat(iterations) {
            var changed = false
            for (i in points.indices) {
                val c = nearest(centers, points[i])
                if (c != assign[i]) { assign[i] = c; changed = true }
            }
            val sl = DoubleArray(centers.size); val sa = DoubleArray(centers.size); val sb = DoubleArray(centers.size)
            val n = IntArray(centers.size)
            for (i in points.indices) {
                val c = assign[i]; val p = points[i]
                sl[c] += p.l; sa[c] += p.a; sb[c] += p.b; n[c]++
            }
            for (c in centers.indices) if (n[c] > 0) centers[c] = Lab(sl[c] / n[c], sa[c] / n[c], sb[c] / n[c])
            if (!changed && it > 0) return centers
        }
        return centers
    }

    fun nearest(centers: List<Lab>, p: Lab): Int {
        var best = 0; var bestD = Double.MAX_VALUE
        for (c in centers.indices) {
            val d = centers[c].dist2(p)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }
}
