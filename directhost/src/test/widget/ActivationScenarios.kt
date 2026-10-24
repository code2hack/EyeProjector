package com.code2hack.eyeprojector.directhost.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Handler
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*

fun main(){
    val context=Context();val b=HostBootstrap.current
    b.value=HostBootstrap.Status(HostBootstrap.Setup.UNAVAILABLE,"UNKNOWN")
    val s=PhoneSessionController.get(context)
    val lane=s.javaClass.getDeclaredField("featureLane").apply{isAccessible=true}.get(s) as ScheduledExecutorService
    fun settle(){Handler.drain();lane.submit{}.get(2,TimeUnit.SECONDS);Handler.drain();lane.submit{}.get(2,TimeUnit.SECONDS);Handler.drain()}
    try {
        PhoneWidgetProvider().onUpdate(context,AppWidgetManager.manager,AppWidgetManager.manager.ids)
        val h=s.requestActivation(ActivationCause.NOTIFICATION);settle()
        val views=AppWidgetManager.manager.updates.getValue(41)
        val token=views.intents.getValue(R.id.phone_widget_primary)
        val captured=PhoneWidgetIntents.read(token.intent)!!
        check(captured.command==PhoneWidgetCommand.STOP && captured.activationId==h.id)
        check(token.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        check(PhoneWidgetIntents.read(PhoneWidgetIntents.intent(context,41,captured))==captured)
        check(PhoneWidgetIntents.pending(context,41,captured.copy(activationId="another-H"))!==token)
        println("PASS actual renderer/intent carries pending whole H in immutable PendingIntent identity")
        val action=token.send();settle()
        check(action.finished && !s.isActivationCurrent(h) && b.starts.isEmpty())
        println("PASS actual widget Activity cancels pending whole H without waiting for UNKNOWN/setup backend")
        val next=s.requestActivation(ActivationCause.HID_PRESS);settle()
        check(next.id!=h.id)
        token.send();settle();check(s.isActivationCurrent(next))
        println("PASS stale original whole-H widget token cannot cancel newer pending H")
        b.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED"));settle()
        check(b.starts.size==1 && s.isActivationCurrent(next))
        println("PASS canceled H stays retired; next allowed trigger retries when actual setup becomes available")
    } finally {Handler.drain();PhoneWidgetUpdates.disabled();lane.shutdownNow();lane.awaitTermination(2,TimeUnit.SECONDS)}
}
