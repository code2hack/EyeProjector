package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.input.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit failing-before for the separately pending queue commit seam98. */
internal fun queuedHomeBefore(j:Joined) {
    val source=(j.phone.session as CapturedProjection).uiTargetContext()!!
    val fact=(j.phone.session as CapturedProjection).receiverUsability().fact!!
    val geometry=Geometry(480,640)
    val snapshot=UiTargetSnapshotProvider(ManagedUiSourceProvider{source},{j.windows.reader},{0})
        .read(source.context,"app")
    val target=(AnchorTargetResolver.resolve(savedHold.descriptor,snapshot) as AnchorTargetResolution.Unique).current
    val setting=j.runtime.store.snapshot("app").context
    check(j.input.anchor.reconcile(setting))
    val entered=CountDownLatch(1);val release=CountDownLatch(1)
    j.phone.adapter(object:ProjectionLaunchAdapter {
        override fun foregroundComponent(displayId:Int):String {
            entered.countDown();check(release.await(2,TimeUnit.SECONDS));return "app/.Main"
        }
        override fun foreground(displayId:Int)="app"
        override fun launch(displayId:Int,packageName:String,home:Boolean):ProjectionCommandResult {
            j.appLaunches.add(packageName);return ProjectionCommandResult(0,"Status: ok\n")
        }
        override fun close()=Unit
    })
    val work=FunctionWork()
    try {
        j.phone.receive(Frame(Wire.NAVIGATE,payload=Navigate(Navigation.CONTEXTUAL_HOME,geometry).bytes()))
        check(entered.await(2,TimeUnit.SECONDS))
        val intent=FunctionIntent(FunctionIntentKind.ACTIVATE,FunctionSource.PHONE_HID,900,900,
            FunctionIntentContext(fact,HeadPointerState(fact.inputRevision,1,true,true,10f,20f,Fit(0,0,480,640),geometry)))
        val admission=j.input.anchor.capture(intent)!!
        val merger=j.phone.get("phoneInput") as PhoneInputMerger
        check(j.input.hold(admission,setting,target,source,{true},merger::nativeAction,AnchorHoldOutput(work){_,_->})==
            AnchorExecutionStatus.HOLD_SUBMITTED)
        check(j.events.single().phase==Touch.Phase.DOWN)
    } finally {release.countDown();work.close()}
    j.launches.snapshot(true) // Queue barrier after the actual unscoped beforeLaunch and platform effect.
    check(j.input.anchor.holding&&j.events.size==1&&j.appLaunches.isEmpty()) {
        "Stale queued Home canceled successor/launched: events=${j.events}; launches=${j.appLaunches}"
    }
}

/** Inject a genuine accepted same-Session H2 while its app FIFO is deterministically parked. */
internal fun startResolvedHold(j:Joined) {
    val source=(j.phone.session as CapturedProjection).uiTargetContext()!!
    val fact=(j.phone.session as CapturedProjection).receiverUsability().fact!!
    val geometry=Geometry(480,640)
    val snapshot=UiTargetSnapshotProvider(ManagedUiSourceProvider{source},{j.windows.reader},{0}).read(source.context,"app")
    val target=(AnchorTargetResolver.resolve(savedHold.descriptor,snapshot) as AnchorTargetResolution.Unique).current
    val setting=j.runtime.store.snapshot("app").context
    val intent=FunctionIntent(FunctionIntentKind.ACTIVATE,FunctionSource.PHONE_HID,990,990,
        FunctionIntentContext(fact,HeadPointerState(fact.inputRevision,1,true,true,10f,20f,Fit(0,0,480,640),geometry)))
    val admission=j.input.anchor.capture(intent)!!
    val merger=j.phone.get("phoneInput") as PhoneInputMerger
    val work=j.phone.get("functionWork") as FunctionWork
    check(j.input.hold(admission,setting,target,source,{true},merger::nativeAction,AnchorHoldOutput(work){_,_->})==AnchorExecutionStatus.HOLD_SUBMITTED)
}
