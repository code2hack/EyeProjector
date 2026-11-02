package com.code2hack.eyeprojector.directhost

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo as Window
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directrg.AnchorRegistrationAction
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.atomic.AtomicReference

/** Actual raw50 Android adapter -> reader -> original Shell/Binder/FIFO provider -> GL chooser -> store. */
internal fun rawRegistrationScenarios(run:(String,(Joined)->Unit)->Unit) {
    for(action in AnchorRegistrationAction.entries) run("Doubao-raw50-actual-provider-menu-release-$action") { j ->
        val raw=capturedVoiceNodes();val handles=mutableListOf<Node>();val windows=mutableListOf<Window>()
        val size=Geometry(840,1120)
        j.display.javaClass.getDeclaredField("capturedDisplayId").apply{isAccessible=true}.set(j.display,57)
        j.display.javaClass.getDeclaredField("observedSource").apply{isAccessible=true}.set(j.display,
            ManagedProjectionDisplay.SourceObservation(Geometry(1248,1663),size,420,0,false))
        j.display.inputGeometry(size)
        @Suppress("UNCHECKED_CAST") (j.phone.get("geometry") as AtomicReference<Geometry?>).set(size)
        j.input.geometry(size)
        j.component="com.larus.nova/.Main"
        @Suppress("UNCHECKED_CAST")
        val displayState=j.launches.javaClass.getDeclaredField("state").apply{isAccessible=true}.get(j.launches) as AtomicReference<ProjectionDisplayState>
        displayState.updateAndGet{it.copy(displayId=57)} // Configure the fixture display observation, not source admission.
        j.launches.snapshot(true)
        UiTargetAccess.currentReader=UiTargetReader(object:UiAccess {
            override val canRead=true
            override fun windowsOnAllDisplays():Map<Int,List<UiAccessWindow>> {
                check(handles.all{it.closed==1}&&windows.all{it.closed==1})
                fun build(n:RawVoiceNode):Node=Node(packageName="com.larus.nova",windowId=4160,viewIdResourceName=n.resourceId,
                    className=n.className,screen=Rect(n.bounds[0],n.bounds[1],n.bounds[2],n.bounds[3]),
                    isClickable=n.clickable,isLongClickable=n.longClickable,isVisibleToUser=n.visible,isEnabled=n.enabled,
                    actionList=n.actions.map{Node.AccessibilityAction(it)},
                    children=raw.filter{it.parent==n.ordinal}.sortedBy{it.childIndex}.map(::build)).also(handles::add)
                val root=build(raw.single{it.parent==null})
                val window=Window(57,4160,1,0,true,true,root,Rect(0,0,1248,1663)).also(windows::add)
                return AndroidUiAccess(AccessibilityService(windowsOnAllDisplays=SparseArray(mapOf(57 to listOf(window))))).windowsOnAllDisplays()
            }
        },{0L})
        val source=j.glasses.session
        source.javaClass.getDeclaredField("geometry").apply{isAccessible=true}.set(source,size)
        j.wiring.currentGeometry=size;j.wiring.layout(480,640);j.wiring.move(201.89757f,609.461f);j.pump()
        val before=j.runtime.store.snapshot("com.larus.nova")
        val at=android.os.SystemClock.uptimeMillis()
        j.wiring.register(at);j.pump()
        check(j.wiring.active()) {"Actual raw target did not reach chooser: ${j.messages}; diagnostic=${j.runtime.coordinator.lastRegistrationDiagnostic?.render()}; current=${(j.phone.session as CapturedProjection).receiverUsability()}"}
        val selected=j.runtime.coordinator.lastRegistrationDiagnostic!!.inspection!!
        check(selected.selected?.resourceId=="com.larus.nova:id/speak_normal")
        check(selected.videoHover==UiPoint(353.0,1066.0))
        check(kotlin.math.abs(selected.sourceHover!!.y-1584.2341376228776)<0.000001)
        check(j.events.isEmpty()&&j.ordinary.isEmpty()&&j.runtime.store.snapshot("com.larus.nova")==before)
        j.wiring.choose(action);j.pump()
        check(!j.wiring.active()&&j.events.isEmpty()&&j.ordinary.isEmpty())
        val after=j.runtime.store.snapshot("com.larus.nova")
        if(action==AnchorRegistrationAction.CANCEL)check(after==before)
        else {
            check(after.anchor!!.descriptor.resourceId=="com.larus.nova:id/speak_normal")
            check(after.anchor!!.mode==if(action==AnchorRegistrationAction.CLICK)AnchorMode.CLICK else AnchorMode.HOLD)
        }
        check(handles.isNotEmpty()&&handles.all{it.closed==1}&&windows.all{it.closed==1})
    }
}
