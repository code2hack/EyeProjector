package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.directhost.apps.ChosenApp

class ProjectedHomeView(context: Context) {
    var onAppSelected: ((ChosenApp) -> Unit)? = null
    val statuses = mutableListOf<String>()
    fun showStatus(value: String) { statuses.add(value) }
    fun refresh() {}
}

object R { object string {
    const val projected_home_opening = 1
    const val projected_home_opened = 2
    const val projected_home_timeout = 3
    const val projected_home_unconfirmed = 4
    const val projected_home_unavailable = 5
    const val host_status = 10
    const val host_start = 11
    const val host_stop = 12
    const val host_authorize = 13
    const val host_open_shizuku = 14
    const val host_setup_guide = 15
    const val host_guide_address = 16
    const val host_shizuku_missing = 17
    const val host_shizuku_unavailable = 18
    const val host_shizuku_update = 19
    const val host_shizuku_authorize = 20
    const val host_shizuku_shell = 21
    const val host_shizuku_available = 22
    const val host_forget_pending = 23
    const val host_session_missing = 24
    const val host_bind_pending = 25
    const val host_listening = 26
    const val host_starting = 27
    const val host_backend_streaming = 28
    const val host_stopped = 29
    const val host_stopping = 30
    const val host_stop_unknown = 31
    const val host_failed = 32
    const val host_unknown = 33
    const val host_start_failed = 34
} }
