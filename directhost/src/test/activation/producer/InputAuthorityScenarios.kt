package com.code2hack.eyeprojector.directhost.activation.producer

import android.os.Handler
import android.view.InputDevice
import android.view.KeyEvent
import com.code2hack.eyeprojector.directhost.hid.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.protocol.*
import rikka.shizuku.Shizuku
import java.util.concurrent.*

/** Actual Bootstrap/facade/Runtime/KeyService/controller; Android/Binder are explicit fixtures. */
internal fun inputAuthorityRegression(){
 val failures=mutableListOf<String>()
 for(bound in listOf(false,true))for(deliverAfterRestore in listOf(false,true))Fixture().use{f->
  PhoneHidRuntime::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
  val runtime=PhoneHidRuntime.get(f.context);val ingress=PhoneHidKeyService();ingress.onServiceConnected()
  val release=CountDownLatch(1);val entered=CountDownLatch(1)
  try {
   val d=f.ready().handle
   f.report(ReceiverUsabilityMode.LOCAL_PANEL);Handler.flush()
   check(!f.sessions.needsActivation()) // Input stays normal although the original PROJECTED D is pending.
   val device=InputDevice(42,"actual-producer-hid",7,8,"External fixture")
   val key=201;val scan=801
   if(bound)runtime.controller.bindings.assign(PhoneHidAction.TAP,PhoneHidBinding(
    PhoneHidButton(PhoneHidDeviceIdentity(device.descriptor,device.vendorId,device.productId),key,scan),device.name,"tap"))
   fun edge(at:Long,down:Long=at,phase:Int=KeyEvent.ACTION_DOWN,k:Int=key,sc:Int=scan)=
    ingress.onKeyEvent(KeyEvent(device,phase,k,sc,down,at))
   // An unassigned gesture already passed before loss keeps that exact original decision.
   check(!edge(1000,k=key+1,sc=scan+1))
   f.lane.execute{entered.countDown();check(release.await(4,TimeUnit.SECONDS))};check(entered.await(1,TimeUnit.SECONDS))
   val original=f.service.context;val starts=f.service.starts;val stops=f.service.stops
   Shizuku.available=false;f.bootstrap.refresh();f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
   check(f.sessions.needsActivation())
   Shizuku.available=true;f.bootstrap.refresh();f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
   check(f.bootstrap.status().projectionContext==original)
   if(deliverAfterRestore)Handler.flush() // Both queued callbacks see the final restored fact; neither is delivered during loss.
   val requires=f.sessions.needsActivation();val calls=f.service.inputCalls.size
   val handled=edge(2000)
   val noTarget=f.service.inputCalls.size==calls
   val passedUp=edge(2020,1000,KeyEvent.ACTION_UP,k=key+1,sc=scan+1)
   val okay=requires && handled && noTarget && !passedUp
   val label="bound=$bound mainAfterRestore=$deliverAfterRestore"
   println("OBSERVED actual input authority ABA $label needsWake=$requires DOWNowned=$handled targetCalls=${f.service.inputCalls.size-calls} oldPassedUP=$passedUp")
   if(!okay){failures.add(label);println("COUNTEREXAMPLE actual producer/HID ABA $label: stale input publication revived; NOT WAKE PASS")}
   release.countDown();f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS)
   f.report(ReceiverUsabilityMode.LOCAL_PANEL);Handler.flush()
   check(!f.sessions.needsActivation() && f.sessions.isActivationCurrent(d))
   check(f.service.starts==starts && f.service.stops==stops)
   val after=f.service.inputCalls.size
   check(edge(2040,2000,KeyEvent.ACTION_UP)==handled)
   if(okay)check(f.service.inputCalls.size==after) // Fresh readiness cannot replay the owned wake release.
   val normal=edge(3000);check(normal==bound);check(edge(3020,3000,KeyEvent.ACTION_UP)==bound)
   if(okay)println("PASS actual input authority ABA $label keeps full wake/old passed gesture; fresh local input remains normal while PROJECTED D waits")
  } finally {
   release.countDown();ingress.onDestroy()
   val writer=runtime.javaClass.getDeclaredField("writer").apply{isAccessible=true}.get(runtime) as ExecutorService
   writer.shutdownNow();writer.awaitTermination(2,TimeUnit.SECONDS)
  }
 }
 check(failures.isEmpty()){ "Actual source input authority ABA cases failed: $failures" }
}
