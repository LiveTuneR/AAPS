package app.aaps.implementation.notifications

/** Idle registry owns no timer. Preserve the 30s validity/clock-change check only while expiry is relevant. */
internal fun notificationExpiryDelay(expirations: List<Long>, hasValidityCheck: Boolean, now: Long): Long? {
    val deadlines = expirations.filter { it != 0L }
    if (deadlines.isEmpty() && !hasValidityCheck) return null
    return minOf(30_000L, deadlines.minOrNull()?.let { (it - now + 1).coerceAtLeast(1) } ?: 30_000L)
}
