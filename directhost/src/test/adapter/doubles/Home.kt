package com.code2hack.eyeprojector.directhost
import android.content.Context
import com.code2hack.eyeprojector.directhost.apps.ChosenApp
class ProjectedHomeView(context:Context){
    var onAppSelected:((ChosenApp)->Unit)?=null
    val statuses=mutableListOf<String>()
    fun showStatus(value:String){statuses.add(value)}
    fun refresh(){}
}
class PhoneSessionController {
    internal fun tryCommitActivationReady(ready: com.code2hack.eyeprojector.directhost.activation.ActivationReady<ProjectionContext>,
        permit: com.code2hack.eyeprojector.directhost.activation.LaunchCommitPermit): Boolean =
        error("Notification claim is outside this legacy adapter fixture; actual composition has a separate suite")
    fun launchApp(pkg:String,expected:ProjectionContext,complete:(ProjectionDisplayState?)->Unit)=host.launchApp(pkg,expected,complete)
    companion object {
        lateinit var host:HostBootstrap
        fun get(context:Context)=PhoneSessionController()
    }
}
object R { object string {
    const val projected_home_opening=1
    const val projected_home_opened=2
    const val projected_home_timeout=3
    const val projected_home_unconfirmed=4
    const val projected_home_unavailable=5
} }
