package com.code2hack.eyeprojector.directhost

import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.view.Surface
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.ScrcpyRecord

/**
 * A shell-owned VDM display rendering directly into its AVC encoder's input Surface.
 * Creation, drain/reconfigure and close are blocking owner operations, off control/output locks.
 * No CDM association is created here, and an unavailable custom camera policy never falls back.
 */
internal class ManagedProjectionDisplay private constructor(
    private val status: (String, Int?, Int?) -> Unit,
) : Closeable {
    class Failure(val category: String, cause: Throwable? = null) : Exception(category, cause) {
        // Keep an incomplete original cleanup reachable through its reported failure.
        internal var retainedOwner: ManagedProjectionDisplay? = null
    }

    private data class ShellContextObservation(
        val route: String,
        val packageName: String?,
        val opPackageName: String?,
        val applicationUid: Int?,
        val attributionUid: Int?,
        val attributionPackageName: String?,
        val readFailureTypes: List<String>,
    )
    @Volatile private var observedShellContexts: List<ShellContextObservation> = emptyList()
    private data class DeviceCreateFailure(
        val phase: String,
        val exceptionClass: String,
        val causeChainClasses: List<String>,
        val frameworkMessage: String?,
    )
    @Volatile private var deviceCreateFailure: DeviceCreateFailure? = null
    private data class ManagerContextObservation(
        val sourceApplicationContextNull: Boolean?,
        val suppliedApplicationContextNull: Boolean?,
        val managerContextNull: Boolean?,
        val managerApplicationContextNull: Boolean?,
        val readFailureClasses: List<String>,
    )
    @Volatile private var managerContextObservation: ManagerContextObservation? = null

    /** Local owner plumbing only; identity and attribution still come from the real Shell context. */
    private class ShellApplicationContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    /** Safe scalar observations only; neither package metadata nor a local comparison grants access. */
    fun shellContextStatus(): JSONObject = JSONObject().put("callerUid", Process.myUid())
        .put("contexts", JSONArray().also { rows -> observedShellContexts.forEach { value ->
            rows.put(JSONObject().put("route", value.route)
                .put("packageName", value.packageName ?: JSONObject.NULL)
                .put("opPackageName", value.opPackageName ?: JSONObject.NULL)
                .put("applicationInfoUid", value.applicationUid ?: JSONObject.NULL)
                .put("attributionUid", value.attributionUid ?: JSONObject.NULL)
                .put("attributionPackageName", value.attributionPackageName ?: JSONObject.NULL)
                .put("readFailureTypes", JSONArray().also { failures -> value.readFailureTypes.forEach { failures.put(it) } }))
        } })
        .put("virtualDeviceCreateFailure", deviceCreateFailure?.let { value ->
            JSONObject().put("phase", value.phase).put("exceptionClass", value.exceptionClass)
                .put("causeChainClasses", JSONArray().also { classes -> value.causeChainClasses.forEach { classes.put(it) } })
                .put("frameworkMessage", value.frameworkMessage ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        .put("virtualDeviceManagerContext", managerContextObservation?.let { value ->
            JSONObject().put("route", "EXPLICIT_REAL_SERVICE_AND_LOCAL_SHELL_CONTEXT")
                .put("sourceApplicationContextNull", value.sourceApplicationContextNull ?: JSONObject.NULL)
                .put("suppliedApplicationContextNull", value.suppliedApplicationContextNull ?: JSONObject.NULL)
                .put("managerContextNull", value.managerContextNull ?: JSONObject.NULL)
                .put("managerApplicationContextNull", value.managerApplicationContextNull ?: JSONObject.NULL)
                .put("readFailureClasses", JSONArray().also { classes -> value.readFailureClasses.forEach { classes.put(it) } })
        } ?: JSONObject.NULL)

    private fun createVirtualDeviceManager(context: Context, managerType: Class<*>): Any {
        // A user-service has no attached Application. VirtualDeviceInternal dereferences
        // getApplicationContext(), so provide a local context whose identity delegates unchanged.
        val ownerContext = ShellApplicationContext(context)
        observeShellContext("VDM_LOCAL_APPLICATION_CONTEXT", ownerContext)
        val failures = ArrayList<String>()
        fun <T> scalar(read: () -> T): T? = try { read() } catch (error: Throwable) {
            failures.add(unwrap(error).javaClass.name); null
        }
        val sourceApplicationNull = scalar { context.applicationContext == null }
        val suppliedApplicationNull = scalar { ownerContext.applicationContext == null }
        managerContextObservation = ManagerContextObservation(sourceApplicationNull,
            suppliedApplicationNull, null, null, failures.toList())

        val serviceType = Class.forName("android.companion.virtual.IVirtualDeviceManager")
        val serviceName = Context::class.java.getField("VIRTUAL_DEVICE_SERVICE").get(null) as String
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, serviceName) as? IBinder ?: throw Failure("VDM_SERVICE_UNAVAILABLE")
        val service = Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw Failure("VDM_SERVICE_UNAVAILABLE")
        val manager = managerType.getDeclaredConstructor(serviceType, Context::class.java)
            .apply { isAccessible = true }.newInstance(service, ownerContext)
        val storedContext = runCatching {
            managerType.getDeclaredField("mContext").apply { isAccessible = true }.get(manager) as? Context
        }
        storedContext.exceptionOrNull()?.let { failures.add(unwrap(it).javaClass.name) }
        val managerContext = storedContext.getOrNull()
        managerContextObservation = ManagerContextObservation(sourceApplicationNull,
            suppliedApplicationNull, if (storedContext.isSuccess) managerContext == null else null,
            managerContext?.let { scalar { it.applicationContext == null } }, failures.toList())
        return manager
    }

    private fun observeShellContext(route: String, context: Context) {
        val failures = ArrayList<String>()
        fun <T> scalar(read: () -> T): T? = try { read() } catch (error: Throwable) {
            failures.add(unwrap(error).javaClass.simpleName); null
        }
        val attribution = scalar { context.attributionSource }
        val observation = ShellContextObservation(route,
            scalar { context.packageName },
            scalar { Context::class.java.getMethod("getOpPackageName").invoke(context) as? String },
            scalar { context.applicationInfo.uid },
            attribution?.uid, attribution?.packageName, failures.toList())
        observedShellContexts = observedShellContexts + observation
    }

    private var originalDevice: Any? = null
    private var originalDisplay: VirtualDisplay? = null
    private val displayOperations = Any()
    private val inputOperations = Any()
    private data class InputMapping(val video: Geometry, val source: Geometry)
    private var inputMapping: InputMapping? = null
    private var observedSourceGeometry: Geometry? = null
    private var sourceTransitioning = false
    private val resizeRequested = AtomicReference<Geometry?>()
    private val displayChanged = AtomicBoolean()
    // Every epoch, including a partially configured or failed original, remains owned here.
    private val originalEncoders = ArrayList<ManagedProjectionEncoder>()
    private var activeEncoder: ManagedProjectionEncoder? = null
    private var originalDisplayManager: DisplayManager? = null
    private var originalDisplayListener: DisplayManager.DisplayListener? = null
    private var originalEventThread: HandlerThread? = null
    @Volatile private var capturedDisplayId: Int? = null
    @Volatile private var capturedDeviceId: Int? = null
    private val closeRequested = AtomicBoolean()
    private val closeComplete = CountDownLatch(1)
    @Volatile private var closingThread: Thread? = null
    @Volatile private var closeFailure: Failure? = null

    /** IDs are observations of these exact original resources, including after their retirement. */
    val sourceDisplayId: Int get() = capturedDisplayId ?: throw Failure("DISPLAY_NOT_CREATED")
    val deviceId: Int get() = capturedDeviceId ?: throw Failure("DEVICE_NOT_CREATED")
    val sourceDisplayIdOrNull: Int? get() = capturedDisplayId
    val deviceIdOrNull: Int? get() = capturedDeviceId

    /** Queue only: LiveScale invokes this from its controller, never run codec/display work there. */
    fun resize(width: Int, height: Int) = synchronized(inputOperations) {
        if (closeRequested.get()) throw Failure("DISPLAY_CLOSED")
        inputMapping = null
        resizeRequested.set(Geometry(width, height))
    }

    fun inputGeometry(next: Geometry) = synchronized(inputOperations) {
        if (closeRequested.get()) return@synchronized
        val source = observedSourceGeometry ?: return@synchronized
        if (!sourceTransitioning && resizeRequested.get() == null) inputMapping = InputMapping(next, source)
    }

    fun withInputMapping(video: Geometry, action: (Int, Int, Int) -> Boolean): Boolean =
        synchronized(inputOperations) {
            if (closeRequested.get()) return@synchronized false
            if (sourceTransitioning || resizeRequested.get() != null || displayChanged.get()) return@synchronized false
            val mapping = inputMapping ?: return@synchronized false
            if (mapping.video != video) return@synchronized false
            if (observedSourceGeometry != mapping.source) return@synchronized false
            action(sourceDisplayId, mapping.source.width, mapping.source.height)
        }

    private fun observeSourceGeometry(): Geometry {
        val display = originalDisplay ?: throw Failure("DISPLAY_NOT_CREATED")
        val size = Point().also(display.display::getRealSize)
        val observed = Geometry(size.x, size.y)
        synchronized(inputOperations) {
            if (observedSourceGeometry != observed) inputMapping = null
            observedSourceGeometry = observed
        }
        return observed
    }

    private fun attachEncoder(geometry: Geometry) {
        val encoder = ManagedProjectionEncoder()
        originalEncoders.add(encoder)
        activeEncoder = encoder
        encoder.prepare(geometry)
        originalDisplay!!.surface = encoder.inputSurface
        report("DISPLAY_ENCODER_ATTACHED")
    }

    /** Single stream reader. Close takes the same ownership lock after this native turn returns. */
    fun readRecord(keepRunning: () -> Boolean): ScrcpyRecord? {
        try {
            return synchronized(displayOperations) {
                while (!closeRequested.get() && keepRunning()) {
                    val original = originalDisplay ?: throw Failure("DISPLAY_NOT_CREATED")
                    val (requested, changed) = synchronized(inputOperations) {
                        val next = resizeRequested.getAndSet(null)
                        val dirty = displayChanged.getAndSet(false)
                        if (next != null || dirty) sourceTransitioning = true
                        next to dirty
                    }
                    if (requested != null) original.resize(requested.width, requested.height, DENSITY_DPI)
                    if (requested != null || changed) {
                        val observed = observeSourceGeometry()
                        // Even a no-op request needs a fresh observed epoch: each GEOMETRY
                        // replaces the receiver decoder and must precede new CSD and frame data.
                        if (requested != null || activeEncoder?.sourceGeometry != observed) {
                            synchronized(inputOperations) { inputMapping = null }
                            original.surface = null
                            activeEncoder?.close()
                            attachEncoder(observed)
                        }
                        synchronized(inputOperations) { sourceTransitioning = false }
                    }
                    activeEncoder?.readRecord()?.let { return@synchronized it }
                }
                null
            }
        } catch (error: Throwable) {
            report("DISPLAY_ENCODER_STREAM_FAILED")
            throw if (error is Failure) error else Failure("DISPLAY_ENCODER_STREAM_FAILED", unwrap(error))
        }
    }

    private fun report(category: String) {
        // The optional observer does not own resource creation or release.
        runCatching { status(category, capturedDisplayId, capturedDeviceId) }
    }

    private fun initialize() {
        var stage = "SHELL_UID_REQUIRED"
        try {
            if (Process.myUid() != 2000) throw Failure(stage)
            stage = "VDM_API_UNAVAILABLE"
            if (Build.VERSION.SDK_INT < 35) throw Failure(stage)
            stage = "DISPLAY_EVENT_THREAD_START_FAILED"
            val thread = HandlerThread("eyeprojector-managed-display-events")
            originalEventThread = thread
            thread.start()
            val handler = Handler(thread.looper)
            stage = "SHELL_CONTEXT_UNAVAILABLE"
            // systemMain constructs framework Handlers. Run it on the retained real Looper when
            // this shell process does not already have an ActivityThread.
            val contextTask = FutureTask { shellContext(::observeShellContext) }
            if (!handler.post(contextTask)) throw Failure("SHELL_CONTEXT_DISPATCH_FAILED")
            val context = contextTask.get()
            report("SHELL_CONTEXT_READY")

            stage = "ASSOCIATION_QUERY_FAILED"
            val cdm = context.getSystemService(CompanionDeviceManager::class.java)
                ?: throw Failure("CDM_SERVICE_UNAVAILABLE")
            // These are the profiles accepted by Android's VDM service, not locally invented IDs.
            val profiles = listOf("DEVICE_PROFILE_APP_STREAMING", "DEVICE_PROFILE_AUTOMOTIVE_PROJECTION",
                "DEVICE_PROFILE_NEARBY_DEVICE_STREAMING").map {
                AssociationRequest::class.java.getField(it).get(null) as String
            }
            val associations = cdm.myAssociations
            val association = profiles.firstNotNullOfOrNull { profile ->
                associations.firstOrNull { it.deviceProfile == profile }
            } ?: throw Failure("ASSOCIATION_REQUIRED")
            report("ASSOCIATION_READY")

            stage = "VDM_API_UNAVAILABLE"
            val managerType = Class.forName("android.companion.virtual.VirtualDeviceManager")
            stage = "VDM_MANAGER_CREATE_FAILED"
            val manager = createVirtualDeviceManager(context, managerType)
            stage = "VDM_API_UNAVAILABLE"
            val paramsType = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val builderType = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val cameraPolicy = paramsType.getField("POLICY_TYPE_CAMERA").getInt(null)
            val customPolicy = paramsType.getField("DEVICE_POLICY_CUSTOM").getInt(null)
            val builder = builderType.getConstructor().newInstance()
            builderType.getMethod("setName", String::class.java).invoke(builder, "EyeProjector projection")
            builderType.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(builder,
                paramsType.getField("LOCK_STATE_ALWAYS_UNLOCKED").getInt(null))
            builderType.getMethod("setDevicePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(builder, cameraPolicy, customPolicy)
            val params = builderType.getMethod("build").invoke(builder)
            // Android may strip the policy when the virtual-camera feature flag is disabled.
            stage = "CAMERA_POLICY_UNAVAILABLE"
            if (paramsType.getMethod("getDevicePolicy", Int::class.javaPrimitiveType)
                    .invoke(params, cameraPolicy) != customPolicy) throw Failure(stage)

            stage = "VIRTUAL_DEVICE_CREATE_FAILED"
            var createPhase = "METHOD_LOOKUP"
            val device = try {
                val create = managerType.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, paramsType)
                createPhase = "FRAMEWORK_INVOCATION"
                create.invoke(manager, association.id, params) ?: throw Failure(stage)
            } catch (error: Throwable) {
                // This message allowance is confined to this own-association framework call;
                // no TLS, session payload, or other exception messages enter diagnostics.
                val actual = unwrap(error)
                val classes = ArrayList<String>()
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                seen.add(actual)
                var cause = actual.cause
                while (cause != null && classes.size < 8 && seen.add(cause)) {
                    classes.add(cause.javaClass.name)
                    cause = cause.cause
                }
                deviceCreateFailure = DeviceCreateFailure(createPhase, actual.javaClass.name,
                    classes.toList(), actual.message?.take(300))
                throw error
            }
            originalDevice = device
            capturedDeviceId = device.javaClass.getMethod("getDeviceId").invoke(device) as Int
            stage = "CAMERA_POLICY_UNAVAILABLE"
            if (managerType.getMethod("getDevicePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(manager, deviceId, cameraPolicy) != customPolicy) throw Failure(stage)
            report("VIRTUAL_DEVICE_CREATED")

            stage = "VIRTUAL_DISPLAY_CREATE_FAILED"
            val trusted = DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_TRUSTED").getInt(null)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or trusted
            val display = device.javaClass.getMethod("createVirtualDisplay", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Surface::class.java,
                Int::class.javaPrimitiveType, Executor::class.java, VirtualDisplay.Callback::class.java)
                .invoke(device, WIDTH, HEIGHT, DENSITY_DPI, null, flags, null, null) as? VirtualDisplay
                ?: throw Failure(stage)
            originalDisplay = display
            capturedDisplayId = display.display.displayId
            stage = "DISPLAY_LISTENER_REGISTER_FAILED"
            val displayManager = context.getSystemService(DisplayManager::class.java)
                ?: throw Failure("DISPLAY_MANAGER_UNAVAILABLE")
            originalDisplayManager = displayManager
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(id: Int) = Unit
                override fun onDisplayRemoved(id: Int) { if (id == capturedDisplayId) displayChanged.set(true) }
                override fun onDisplayChanged(id: Int) { if (id == capturedDisplayId) displayChanged.set(true) }
            }
            originalDisplayListener = listener
            displayManager.registerDisplayListener(listener, handler)
            stage = "DISPLAY_ENCODER_CREATE_FAILED"
            attachEncoder(observeSourceGeometry())
            report("VIRTUAL_DISPLAY_CREATED")
        } catch (error: Throwable) {
            val cause = unwrap(error)
            throw if (cause is Failure) cause else Failure(stage, cause)
        }
    }

    override fun close() {
        if (Thread.currentThread() === originalEventThread) throw Failure("DISPLAY_CLOSE_ON_EVENT_THREAD")
        if (!closeRequested.compareAndSet(false, true)) {
            if (Thread.currentThread() === closingThread) throw Failure("DISPLAY_CLOSE_REENTRANT")
            try { closeComplete.await() } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Failure("DISPLAY_CLOSE_WAIT_INTERRUPTED", error)
            }
            closeFailure?.let { throw it }
            return
        }
        closingThread = Thread.currentThread()
        report("VIRTUAL_DISPLAY_CLOSING")
        var failure: Failure? = null
        var interrupted = false
        fun release(category: String, action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val cause = unwrap(error)
                if (cause is InterruptedException) interrupted = true
                val observed = Failure(category, cause)
                if (failure == null) failure = observed else failure!!.addSuppressed(observed)
            }
        }
        try {
            synchronized(inputOperations) { inputMapping = null }
            synchronized(displayOperations) {
                var producerStopped = originalDisplay == null
                originalDisplay?.let { original ->
                    release("DISPLAY_ENCODER_DETACH_FAILED") { original.surface = null; producerStopped = true }
                    release("VIRTUAL_DISPLAY_RELEASE_FAILED") { original.release(); producerStopped = true }
                }
                // If both detach and display release fail, retain the live codec/surface as-is.
                if (producerStopped) originalEncoders.forEach { encoder ->
                    release("DISPLAY_ENCODER_CLOSE_FAILED") { encoder.close() }
                }
            }
            originalDevice?.let { original -> release("VIRTUAL_DEVICE_CLOSE_FAILED") {
                original.javaClass.getMethod("close").invoke(original)
            } }
            originalDisplayListener?.let { original -> release("DISPLAY_LISTENER_REMOVE_FAILED") {
                originalDisplayManager!!.unregisterDisplayListener(original)
            } }
            originalEventThread?.let { original -> release("DISPLAY_EVENT_THREAD_CLOSE_FAILED") {
                if (original.state != Thread.State.NEW) {
                    original.quitSafely()
                    original.join()
                }
            } }
        } finally {
            closeFailure = failure
            failure?.retainedOwner = this
            closeComplete.countDown()
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (failure != null) {
            report("VIRTUAL_DISPLAY_CLOSE_FAILED")
            throw failure!!
        }
        report("VIRTUAL_DISPLAY_CLOSED")
    }

    companion object {
        private const val WIDTH = 480
        private const val HEIGHT = 640
        private const val DENSITY_DPI = 160
        private const val SHELL_PACKAGE = "com.android.shell"

        fun create(status: (String, Int?, Int?) -> Unit = { _, _, _ -> }): ManagedProjectionDisplay {
            val original = ManagedProjectionDisplay(status)
            original.report("VIRTUAL_DISPLAY_CREATING")
            try {
                original.initialize()
                return original
            } catch (error: Throwable) {
                val failure = error as? Failure ?: Failure("VIRTUAL_DISPLAY_CREATE_FAILED", unwrap(error))
                try { original.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                failure.retainedOwner = original
                original.report(failure.category)
                if (failure.cause is InterruptedException) Thread.currentThread().interrupt()
                throw failure
            }
        }

        private fun shellContext(observe: (String, Context) -> Unit): Context {
            val type = Class.forName("android.app.ActivityThread")
            val activityThread = type.getMethod("currentActivityThread").invoke(null)
                ?: type.getMethod("systemMain").invoke(null)
            val system = type.getMethod("getSystemContext").invoke(activityThread) as Context
            val shellPackage = system.createPackageContext(SHELL_PACKAGE, 0)
            observe("PACKAGE_CONTEXT_FROM_SYSTEM", shellPackage)
            // createPackageContext inherits its container's op-package. Use the framework's
            // normal fresh-app-context path with the real Shell LoadedApk, without overriding
            // any UID/package, altering ApplicationInfo, or replacing permission checks.
            val implementation = Class.forName("android.app.ContextImpl")
            val loadedApkType = Class.forName("android.app.LoadedApk")
            val loadedShell = implementation.getDeclaredField("mPackageInfo").apply { isAccessible = true }
                .get(shellPackage)
            val shell = implementation.getDeclaredMethod("createAppContext", type, loadedApkType)
                .apply { isAccessible = true }.invoke(null, activityThread, loadedShell) as Context
            observe("FRESH_SHELL_APP_CONTEXT", shell)
            // CDM/VDM enforce the actual Binder caller/package ownership. ApplicationInfo.uid
            // remains diagnostic metadata, not an extra local admission rule.
            return shell
        }

        private fun unwrap(error: Throwable): Throwable = when (error) {
            is InvocationTargetException -> unwrap(error.targetException)
            is ExecutionException -> error.cause?.let(::unwrap) ?: error
            else -> error
        }
    }
}
