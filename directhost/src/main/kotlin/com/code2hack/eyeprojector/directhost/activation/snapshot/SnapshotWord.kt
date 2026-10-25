package com.code2hack.eyeprojector.directhost.activation.snapshot

/** Local publication sequence, NOT a truncated Receiver revision/epoch or a clock. */
internal object SnapshotWord {
    const val NONE = 0
    const val LOCAL_PANEL = 1
    const val PROJECTED = 2
    const val RETIRED = 3
    const val MAX_STAMP = 0x3fff_ffff_ffff_ffffL
    fun mode(word: Long): Int = (word and 3).toInt()
    fun stamp(word: Long): Long = word ushr 2
    fun pack(stamp: Long, mode: Int): Long {
        require(stamp in 0..MAX_STAMP && mode in NONE..RETIRED)
        return (stamp shl 2) or mode.toLong()
    }
}
