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
    val rootRefresh: UiRootRefresh? get() = null
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
        val counts = TraversalCounts()
        return try {
            val root = window.root() ?: return UiWindowSnapshot(metadata, UiWindowReadStatus.NO_ROOT)
            val targets = ArrayList<UiTargetObservation>()
            val status = collect(root, request.selectedPackage, metadata.windowId, targets, counts)
            UiWindowSnapshot(metadata, status, targets.toList(), traversal = counts.snapshot(window.rootRefresh))
        } catch (error: Exception) {
            UiWindowSnapshot(metadata, UiWindowReadStatus.FAILED, failureClass = error.javaClass.name,
                failure = error, traversal = counts.snapshot(window.rootRefresh))
        }
    }

    private class TraversalCounts {
        var visited = 0; var selected = 0; var invisible = 0; var disabled = 0; var nonPositiveBounds = 0
        var rootPackageName: String? = null
        var root: UiTargetObservation? = null
        var rootChildCount: Int? = null
        fun snapshot(refresh: UiRootRefresh?) = UiWindowTraversal(visited, selected, invisible, disabled, nonPositiveBounds,
            rootPackageName, root, rootChildCount, refresh)
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

    /** Shared immutable root-to-self paths retain filtered ancestors without copying every prefix. */
    private class DrawingPath(val parent: List<UiDrawingOrderStep>, val step: UiDrawingOrderStep) : AbstractList<UiDrawingOrderStep>() {
        override val size = parent.size + 1
        override fun get(index: Int): UiDrawingOrderStep {
            if (index !in 0 until size) throw IndexOutOfBoundsException()
            var path: List<UiDrawingOrderStep> = this
            while (path is DrawingPath) {
                if (index == path.size - 1) return path.step
                path = path.parent
            }
            return path[index]
        }
    }

    private class Frame(val node: UiAccessNode, val ancestors: List<Int>,
        val drawingAncestors: List<UiDrawingOrderStep> = emptyList(), val childIndex: Int = -1,
        var drawingPath: List<UiDrawingOrderStep> = emptyList(), var ordinal: Int = 0,
        var entered: Boolean = false, var nextChild: Int = 0, var children: Int = 0)

    /** Iterative traversal owns root/children even after a stale-node exception; no depth/count cap. */
    private fun collect(root: UiAccessNode, packageName: String, windowId: Int,
        targets: MutableList<UiTargetObservation>, counts: TraversalCounts): UiWindowReadStatus {
        val pending = java.util.ArrayDeque<Frame>()
        pending.push(Frame(root, emptyList()))
        var ordinal = 0
        var selectedRoot = false
        try {
            while (pending.isNotEmpty()) {
                val frame = pending.element()
                if (!frame.entered) {
                    frame.entered = true
                    val nodePackage = frame.node.packageName
                    if (++counts.visited == 1) counts.rootPackageName = nodePackage
                    if (nodePackage != packageName) {
                        pending.pop().node.close()
                        continue
                    }
                    selectedRoot = true
                    counts.selected++
                    if (frame.node.windowId != windowId) return UiWindowReadStatus.WINDOW_MISMATCH.also { targets.clear() }
                    frame.ordinal = ++ordinal
                    val observed = frame.node.observation(frame.ordinal)
                    frame.drawingPath = DrawingPath(frame.drawingAncestors,
                        UiDrawingOrderStep(frame.ordinal, observed?.drawingOrder, frame.childIndex))
                    observed?.copy(ancestorOrdinals = frame.ancestors, drawingOrderPath = frame.drawingPath)?.let { item ->
                        if (counts.visited == 1) counts.root = item
                        if (!item.visible) counts.invisible++
                        if (!item.enabled) counts.disabled++
                        if (!item.bounds.hasArea) counts.nonPositiveBounds++
                        if (item.visible && item.enabled && item.bounds.hasArea)
                            targets.add(item)
                    }
                    frame.children = frame.node.childCount
                    if (counts.visited == 1) counts.rootChildCount = frame.children
                }
                if (frame.nextChild == frame.children) pending.pop().node.close()
                else {
                    val index = frame.nextChild++
                    frame.node.child(index)?.let {
                        pending.push(Frame(it, Ancestors(frame.ancestors, frame.ordinal), frame.drawingPath, index))
                    }
                }
            }
        } finally {
            while (pending.isNotEmpty()) pending.pop().node.close()
        }
        return if (selectedRoot) UiWindowReadStatus.READ else UiWindowReadStatus.OTHER_PACKAGE
    }
}
