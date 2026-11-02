package com.code2hack.eyeprojector.directrg
import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.SystemClock
import com.code2hack.eyeprojector.protocol.AnchorIntentKind

fun main(){
 var failed=0
 for(change in listOf("passive","hover","explicit","lost","normal")){
  Handler.clear();SystemClock.time=1000
  val stableOwner=Any();var hover:Any="original-hover";var connected:Any?=null
  val emitted=mutableListOf<AnchorGesture>();val released=mutableListOf<Boolean>()
  val native=NativeFunctionInput(Context(),{stableOwner},emitted::add,capture={hover},
      registrationRelease={_,c->released.add(c)})
  native.start()
  val pad=NativePadShellContact(HeldInputOwner(),{stableOwner},{PointerPosition(1f,2f,true)},{},{},
      functionSourceAvailable={connected=it;native.rawSourceAvailable(it)},
      functionSourceLost={native.rawSourceLost(it)},
      functionEdge={s,id,down,at,receipt->native.rawEdge(s,id,down,at,receipt)},
      functionCapture={native.captureRawOriginal()})
  val socket=LocalSocket()
  try{
   pad.start();LocalServerSocket.latest!!.queue.put(socket);socket.send("FUNCTION_SOURCE")
   val until=System.nanoTime()+3_000_000_000L
   while(connected==null){check(System.nanoTime()<until);Handler.awaitPosted();Handler.advance(0)}
   socket.send("FUNCTION_DOWN 41 1000");Handler.awaitPosted()
   when(change){
    "passive"->{pad.prepareCancel().finishOriginal();hover="later-hover"}
    "hover"->hover="later-hover"
    "explicit"->native.cancel()
    "lost"->native.rawSourceLost(connected!!)
   }
   Handler.advance(0);Handler.advance(800)
   if(change in listOf("explicit","lost"))check(emitted.isEmpty()){"Consumed/lost original replayed"}
   else{
    check(emitted.singleOrNull()?.kind==AnchorIntentKind.REGISTER){"Original raw DOWN did not register: $emitted"}
    check(emitted.single().original=="original-hover"){"Recaptured later hover: ${emitted.single().original}"}
    check(emitted.single().physicalHeld)
   }
   socket.send("FUNCTION_UP 41 1900");Handler.awaitPosted();Handler.advance(0)
   if(change in listOf("explicit","lost"))check(released.isEmpty())else check(released==listOf(false))
   println("PASS native reader/main $change")
  }catch(e:Throwable){failed++;println("FAIL native reader/main $change: $e");e.printStackTrace()}
  finally{pad.stop();native.stop();socket.close();val until=System.nanoTime()+3_000_000_000L
   while(Thread.getAllStackTraces().keys.any{it.name=="native-pad-socket"&&it.isAlive}&&System.nanoTime()<until)Thread.yield()
   check(Thread.getAllStackTraces().keys.none{it.name=="native-pad-socket"&&it.isAlive})}
 }
 check(failed==0){"$failed native queue failures"}
}
