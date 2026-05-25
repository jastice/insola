package com.insola.uv.domain

import kotlinx.datetime.Instant

/**
 * A logged span of time the user reported they were outside. [end] is null while the session is
 * still open ("currently outside"). The dashboard treats an open session as ending at the current
 * scrubber time when computing accumulated dose.
 */
data class OutdoorSession(
    val start: Instant,
    val end: Instant? = null,
) {
    val isOpen: Boolean get() = end == null

    /** Effective end of this session given a current time. Closed sessions ignore [now]. */
    fun effectiveEnd(now: Instant): Instant = end ?: now

    /** Convert to an [ExposureInterval]. Sessions assume full outdoor exposure (factor 1.0). */
    fun toInterval(now: Instant, factor: Double = 1.0): ExposureInterval =
        ExposureInterval(start = start, end = effectiveEnd(now), exposureFactor = factor)
}
