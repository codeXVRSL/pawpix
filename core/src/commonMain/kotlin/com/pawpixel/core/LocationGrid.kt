package com.pawpixel.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Privacy layer for the (later-phase) pet gatherings map.
 *
 * Research on dating apps showed "approximate distance" leaks exact locations through
 * trilateration: fake your position a few times, measure, intersect. The defence is to never let a
 * real coordinate leave the phone. The device snaps its location to a coarse grid cell here and
 * uploads only the cell id. Everyone in a cell shares the same point, so there is nothing to
 * trilaterate, and the server additionally hides cells with fewer than K owners (see
 * `supabase/migrations`). Distances shown to users are cell-to-cell and bucketed.
 */
object LocationGrid {
    const val DEFAULT_CELL_KM = 1.0
    private const val KM_PER_DEG_LAT = 111.32
    private const val EARTH_RADIUS_KM = 6371.0

    data class Cell(val id: String, val row: Long, val col: Long, val centerLat: Double, val centerLng: Double, val cellKm: Double)

    fun snap(lat: Double, lng: Double, cellKm: Double = DEFAULT_CELL_KM): Cell {
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "coordinates out of range" }
        val latStep = cellKm / KM_PER_DEG_LAT
        val row = floor((lat + 90.0) / latStep).toLong()
        val centerLat = -90.0 + (row + 0.5) * latStep
        val lngStep = latStep / max(cos(centerLat * PI / 180.0), 0.01)
        val col = floor((lng + 180.0) / lngStep).toLong()
        val centerLng = -180.0 + (col + 0.5) * lngStep
        val id = "g${(cellKm * 1000).toInt()}:$row:$col"
        return Cell(id, row, col, centerLat, centerLng, cellKm)
    }

    fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = (bLat - aLat) * PI / 180.0
        val dLng = (bLng - aLng) * PI / 180.0
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(aLat * PI / 180.0) * cos(bLat * PI / 180.0) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Human label for how far another cell is. Same cell never reveals anything finer than "nearby". */
    fun distanceLabel(from: Cell, to: Cell): String {
        if (from.id == to.id) return "Nearby"
        val km = distanceKm(from.centerLat, from.centerLng, to.centerLat, to.centerLng)
        return when {
            km < 10 -> "~${ceil(km).toInt()} km"
            km < 50 -> "~${(ceil(km / 5) * 5).toInt()} km"
            else -> "50+ km"
        }
    }
}
