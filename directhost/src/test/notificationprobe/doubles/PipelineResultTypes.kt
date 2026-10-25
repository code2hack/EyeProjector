package com.code2hack.eyeprojector.directhost

/** Serialization/Binder/source ownership is outside this explicit helper-result boundary. */
class PhoneSessionController
data class ProjectionContext(val ownerEpoch: String = "", val projectionToken: String = "")
enum class ProjectionLaunchStatus { UNAVAILABLE, READY, STARTED, FAILED, TIMED_OUT, CLOSED }
data class ProjectionDisplayState(val requestId: Long = 0, val generation: String = "", val displayId: Int? = null,
    val requestedPackage: String? = null, val status: ProjectionLaunchStatus = ProjectionLaunchStatus.UNAVAILABLE,
    val commandExit: Int? = null, val launchedComponent: String? = null, val foregroundPackage: String? = null,
    val context: ProjectionContext = ProjectionContext())
