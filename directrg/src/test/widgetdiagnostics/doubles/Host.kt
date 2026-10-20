package widgethost

import android.view.View

/** Faults are in host API doubles only; no fixture assertion, wait, or event is rewritten. */
object Host {
    var fixture = 0
    var failure = "none"
    var postCount = 0
    var nativeTouchCount = 0
    var heldTouchCount = 0
    var escapedMainFailure: Throwable? = null
    val operations = mutableListOf<String>()
    var lastInjectedFailure: Exception? = null
    fun reset(nextFailure: String) {
        fixture = 0; failure = nextFailure; postCount = 0
        nativeTouchCount = 0; heldTouchCount = 0; escapedMainFailure = null; lastInjectedFailure = null; operations.clear()
    }
    fun beforeActivityStart() {
        if (failure != "activity-start") return
        var cause: Exception = IllegalArgumentException("sensitive cause message")
        repeat(9) { cause = IllegalStateException("sensitive nested cause message", cause) }
        lastInjectedFailure = cause
        throw cause
    }
    fun beforeIdle() {
        if (failure == "activity-idle") throw IllegalStateException("sensitive idle message")
    }
    fun install(root: View) {
        fixture++
        operations += "install:$fixture"
        root.layout(0, 0, 480, 640)
        if (failure == "native-geometry" && fixture == 1) root.width = 0
        if (failure == "held-geometry" && fixture == 2) root.width = 0
    }
    fun main(task: Runnable) {
        // AOSP SyncRunnable does not hand UI exceptions back to its waiting caller. Here the
        // escape is recorded and returned rather than killing/hanging this host test process.
        // A correct diagnostic wrapper catches inside task and leaves this field empty.
        try { task.run() } catch (error: Throwable) { escapedMainFailure = error }
    }
    fun post(task: Runnable): Boolean {
        postCount++
        if (failure == "native-layout" && fixture == 1 && postCount == 1) return true
        task.run()
        return true
    }
}
