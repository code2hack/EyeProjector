package com.code2hack.eyeprojector.directhost.uiaccess

/** Owned handles are confined to one read. Implementations expose no action or content API. */
internal interface UiAccessNode : AutoCloseable {
    val packageName: String?
    val windowId: Int
    val childCount: Int
    fun child(index: Int): UiAccessNode?
    fun observation(ordinal: Int): UiTargetObservation?
}

internal interface UiAccessWindow : AutoCloseable {
    val metadata: UiWindowMetadata
    fun root(): UiAccessNode?
}

internal interface UiAccess {
    val canRead: Boolean
    fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>>
}

class UiTargetReader internal constructor(private val access: UiAccess, private val uptime: () -> Long) {
    fun read(request: UiTargetRequest): UiTargetSnapshot {
        val started = uptime()
        fun result(status: UiTargetReadStatus, windows: List<UiWindowSnapshot> = emptyList(), error: Exception? = null) =
            UiTargetSnapshot(request, status, started, uptime(), windows, error?.javaClass?.name, error)
        return try {
            if (!access.canRead) return result(UiTargetReadStatus.ACCESS_REQUIRED)
            val all = access.windowsOnAllDisplays()
            try {
                val selected = all[request.source.displayId] ?: return result(UiTargetReadStatus.DISPLAY_ABSENT)
                val windows = selected.filter { request.windowId == null || it.metadata.windowId == request.windowId }
                if (request.windowId != null && windows.isEmpty()) return result(UiTargetReadStatus.WINDOW_ABSENT)
                result(UiTargetReadStatus.READ, windows.map { readWindow(it, request) })
            } finally {
                all.values.forEach { windows -> windows.forEach { it.close() } }
            }
        } catch (error: Exception) {
            result(UiTargetReadStatus.FAILED, error = error)
        }
    }

    private fun readWindow(window: UiAccessWindow, request: UiTargetRequest): UiWindowSnapshot {
        val metadata = window.metadata
        if (metadata.displayId != request.source.displayId) return UiWindowSnapshot(metadata, UiWindowReadStatus.WINDOW_MISMATCH)
        return try {
            val root = window.root() ?: return UiWindowSnapshot(metadata, UiWindowReadStatus.NO_ROOT)
            val targets = ArrayList<UiTargetObservation>()
            val status = collect(root, request.selectedPackage, metadata.windowId, targets)
            UiWindowSnapshot(metadata, status, targets.toList())
        } catch (error: Exception) {
            UiWindowSnapshot(metadata, UiWindowReadStatus.FAILED, failureClass = error.javaClass.name, failure = error)
        }
    }

    /** Immutable shared ancestry links keep even very deep snapshots linear in storage. */
    private class Ancestors(val parent: List<Int>, val ordinal: Int) : AbstractList<Int>() {
        override val size = parent.size + 1
        override fun get(index: Int): Int {
            if (index !in 0 until size) throw IndexOutOfBoundsException()
            var path: List<Int> = this
            while (path is Ancestors) {
                if (index == path.size - 1) return path.ordinal
                path = path.parent
            }
            return path[index]
        }
        override fun contains(element: Int): Boolean {
            var path: List<Int> = this
            while (path is Ancestors) {
                if (path.ordinal == element) return true
                path = path.parent
            }
            return element in path
        }
    }

    private class Frame(val node: UiAccessNode, val ancestors: List<Int>, var ordinal: Int = 0,
        var entered: Boolean = false, var nextChild: Int = 0, var children: Int = 0)

    /** Iterative traversal owns root/children even after a stale-node exception; no depth/count cap. */
    private fun collect(root: UiAccessNode, packageName: String, windowId: Int,
        targets: MutableList<UiTargetObservation>): UiWindowReadStatus {
        val pending = java.util.ArrayDeque<Frame>()
        pending.push(Frame(root, emptyList()))
        var ordinal = 0
        var selectedRoot = false
        try {
            while (pending.isNotEmpty()) {
                val frame = pending.element()
                if (!frame.entered) {
                    frame.entered = true
                    if (frame.node.packageName != packageName) {
                        pending.pop().node.close()
                        continue
                    }
                    selectedRoot = true
                    if (frame.node.windowId != windowId) return UiWindowReadStatus.WINDOW_MISMATCH.also { targets.clear() }
                    frame.ordinal = ++ordinal
                    frame.node.observation(frame.ordinal)?.let { item ->
                        if (item.visible && item.enabled && item.bounds.hasArea)
                            targets.add(item.copy(ancestorOrdinals = frame.ancestors))
                    }
                    frame.children = frame.node.childCount
                }
                if (frame.nextChild == frame.children) pending.pop().node.close()
                else frame.node.child(frame.nextChild++)?.let {
                    pending.push(Frame(it, Ancestors(frame.ancestors, frame.ordinal)))
                }
            }
        } finally {
            while (pending.isNotEmpty()) pending.pop().node.close()
        }
        return if (selectedRoot) UiWindowReadStatus.READ else UiWindowReadStatus.OTHER_PACKAGE
    }
}
