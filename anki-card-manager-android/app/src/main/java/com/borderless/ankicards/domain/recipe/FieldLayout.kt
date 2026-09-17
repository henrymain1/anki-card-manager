package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.Serializable

/**
 * Position and size of a placed field on a card face, in grid coordinates.
 *
 * The grid is a fixed [GRID_COLS] × [GRID_ROWS] lattice. Storing positions as
 * grid cells (not pixels, not fractions) means: layouts are always aligned,
 * overlap checks are integer-rectangle intersections, and the export to
 * `position: absolute` HTML/CSS is a one-liner per field.
 *
 * Coordinates are zero-indexed with the origin at the top-left of the card.
 * `col` runs left→right, `row` runs top→bottom. `w` and `h` are extents (so a
 * 1×1 box occupies exactly one cell).
 *
 * @param col  Leftmost column the field occupies. `0..GRID_COLS-1`.
 * @param row  Topmost row the field occupies. `0..GRID_ROWS-1`.
 * @param w    Width in grid cells. `>= 1` and `col + w <= GRID_COLS`.
 * @param h    Height in grid cells. `>= 1` and `row + h <= GRID_ROWS`.
 */
@Serializable
data class FieldLayout(
    val col: Int,
    val row: Int,
    val w: Int,
    val h: Int
) {
    init {
        require(col in 0 until GRID_COLS) { "col=$col out of range" }
        require(row in 0 until GRID_ROWS) { "row=$row out of range" }
        require(w >= 1 && col + w <= GRID_COLS) { "w=$w invalid for col=$col" }
        require(h >= 1 && row + h <= GRID_ROWS) { "h=$h invalid for row=$row" }
    }

    /**
     * True if [this] and [other] share at least one grid cell. Used to enforce
     * the no-overlap invariant on a single card face.
     */
    fun overlaps(other: FieldLayout): Boolean =
        col < other.col + other.w &&
            other.col < col + w &&
            row < other.row + other.h &&
            other.row < row + h

    companion object {
        const val GRID_COLS = 12
        const val GRID_ROWS = 16
    }
}
