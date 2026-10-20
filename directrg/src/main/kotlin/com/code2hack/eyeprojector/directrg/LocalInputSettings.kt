package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

/** Projector-owned numeric settings; no production browser preferences are accessed. */
internal class LocalInputSettings(
    read: (String) -> String?,
    private val write: (String,String) -> Boolean,
) {
    var pointerXTenths = 20
        private set
    var pointerYTenths = 20
        private set
    val pointerXGain get() = pointerXTenths/10.0
    val pointerYGain get() = pointerYTenths/10.0
    var scrollSpeed = 240
        private set
    var headScrollEnabled = true
        private set
    var error: String? = null
        private set
    init {
        val legacy=read("pointer.tenths")?.toIntOrNull()?.takeIf { it in 1..60 } ?: 20
        val pointerX=read("pointer.x.tenths")
        val pointerY=read("pointer.y.tenths")
        val scroll=read("edge.pixels-per-second")
        val headScroll=read("edge.enabled")
        pointerXTenths=pointerX?.toIntOrNull()?.takeIf { it in 1..60 } ?: legacy
        pointerYTenths=pointerY?.toIntOrNull()?.takeIf { it in 1..60 } ?: legacy
        scrollSpeed=scroll?.toIntOrNull()?.takeIf { it in 10..1200 && it%10==0 } ?: 240
        headScrollEnabled=headScroll?.toBooleanStrictOrNull() ?: true
        // Persist each migrated axis independently; a retry never replaces an already saved axis.
        val pointerXSaved=pointerX==pointerXTenths.toString() || write("pointer.x.tenths",pointerXTenths.toString())
        val pointerYSaved=pointerY==pointerYTenths.toString() || write("pointer.y.tenths",pointerYTenths.toString())
        val scrollSaved=scroll==scrollSpeed.toString() || write("edge.pixels-per-second",scrollSpeed.toString())
        val headScrollSaved=headScroll==headScrollEnabled.toString() || write("edge.enabled",headScrollEnabled.toString())
        if(!pointerXSaved || !pointerYSaved || !scrollSaved || !headScrollSaved) error="Settings could not be saved. Try again."
    }
    fun stepPointerX(direction: Int): Boolean {
        require(direction == -1 || direction == 1)
        val value=(pointerXTenths+direction).coerceIn(1,60)
        return saved("pointer.x.tenths",value.toString()) { pointerXTenths=value }
    }
    fun stepPointerY(direction: Int): Boolean {
        require(direction == -1 || direction == 1)
        val value=(pointerYTenths+direction).coerceIn(1,60)
        return saved("pointer.y.tenths",value.toString()) { pointerYTenths=value }
    }
    fun stepScroll(direction: Int): Boolean {
        require(direction == -1 || direction == 1)
        val value=(scrollSpeed+direction*10).coerceIn(10,1200)
        return saved("edge.pixels-per-second",value.toString()) { scrollSpeed=value }
    }
    fun setHeadScroll(enabled: Boolean): Boolean =
        saved("edge.enabled",enabled.toString()) { headScrollEnabled=enabled }
    private fun saved(key: String, value: String, apply: () -> Unit): Boolean {
        if (!write(key,value)) { error="Settings could not be saved. Try again."; return false }
        apply();error=null;return true
    }
}
