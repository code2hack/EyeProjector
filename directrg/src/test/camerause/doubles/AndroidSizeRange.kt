package android.util

data class Size(val width: Int, val height: Int)
data class Range<T : Comparable<T>>(val lower: T, val upper: T)
