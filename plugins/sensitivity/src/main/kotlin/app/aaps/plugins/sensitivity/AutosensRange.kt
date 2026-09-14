package app.aaps.plugins.sensitivity

/** First ordered timestamp not less than [fromTime]. No arithmetic on timestamps. */
internal fun autosensLowerBound(size: Int, fromTime: Long, keyAt: (Int) -> Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = low + (high - low) / 2
        if (keyAt(middle) < fromTime) low = middle + 1 else high = middle
    }
    return low
}
