package com.meditation.stopwatch.visuals

/**
 * The ordered programme of the session.  Index 0 is the opening movement (shown while idle and for
 * the first segment); once the session is under way the renderer loops over indices 1..n-1.
 */
object Movements {
    val all: List<Movement> = MovementsA.list + MovementsB.list
}
