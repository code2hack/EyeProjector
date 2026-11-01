package android.view

import android.content.Context

/** Stateful Android dispatch/scheduler/detector boundary; not a device or framework measurement. */
open class View(val context: Context = Context()) {
    companion object { const val VISIBLE=0;const val GONE=8 }
    var width=100;var height=100
    var onVisibility:((Int)->Unit)?=null
    var visibility=VISIBLE
        set(value){field=value;onVisibility?.invoke(value)}
    var isClickable=false
    var onEnabled:((Boolean)->Unit)?=null
    var isEnabled=true
        set(value){field=value;onEnabled?.invoke(value)}
    fun setWillNotDraw(value:Boolean)=Unit
    fun invalidate()=Unit
    fun setBackgroundColor(color:Int)=Unit
    open fun onDraw(canvas:android.graphics.Canvas)=Unit
    fun setOnClickListener(listener:(View)->Unit){onClick={listener(this)}}
    val pending=mutableSetOf<Runnable>()
    var onRemove:((Runnable)->Unit)?=null
    var onPost:((Runnable)->Unit)?=null
    var onClick:(()->Unit)?=null
    fun removeCallbacks(task:Runnable):Boolean {val result=pending.remove(task);onRemove?.invoke(task);return result}
    fun postDelayed(task:Runnable,delayMs:Long):Boolean {val result=pending.add(task);onPost?.invoke(task);return result}
    fun post(task:Runnable)=postDelayed(task,0)
    open fun dispatchTouchEvent(event:MotionEvent):Boolean=onTouchEvent(event)
    open fun dispatchGenericMotionEvent(event:MotionEvent):Boolean=false
    open fun onTouchEvent(event:MotionEvent):Boolean=false
    open fun performClick():Boolean {onClick?.invoke();return true}
}
open class SurfaceView(context:Context):View(context){internal val holder=com.code2hack.eyeprojector.directrg.Holder()}
object Gravity {const val CENTER=17;const val BOTTOM=80;const val CENTER_HORIZONTAL=1}
object InputDevice {const val SOURCE_TOUCHSCREEN=0x1002;const val SOURCE_MOUSE=0x2002;const val SOURCE_TOUCHPAD=0x100008;fun supportsSource(source:Int)=true}
class MotionEvent(val downTime:Long,val eventTime:Long,val action:Int,val x:Float,val y:Float) {
    val actionMasked get()=action
    var source=0
    var deviceId=17
    fun isFromSource(value:Int)=source and value==value
    fun getPointerId(index:Int)=0
    fun findPointerIndex(id:Int)=if(id==0)0 else -1
    fun recycle()=Unit
    class PointerCoords {var x=0f;var y=0f;fun setAxisValue(axis:Int,value:Float)=Unit}
    class PointerProperties {var id=0;var toolType=0}
    companion object {
        fun obtain(event:MotionEvent)=MotionEvent(event.downTime,event.eventTime,event.action,event.x,event.y).also{it.source=event.source;it.deviceId=event.deviceId}
        const val ACTION_DOWN=0;const val ACTION_UP=1;const val ACTION_MOVE=2;const val ACTION_CANCEL=3;const val ACTION_SCROLL=8
        const val ACTION_POINTER_DOWN=5;const val ACTION_POINTER_UP=6
        const val AXIS_VSCROLL=9;const val TOOL_TYPE_MOUSE=3
        fun obtain(down:Long,at:Long,action:Int,x:Float,y:Float,meta:Int)=MotionEvent(down,at,action,x,y)
        fun obtain(down:Long,at:Long,action:Int,count:Int,properties:Array<PointerProperties>,coords:Array<PointerCoords>,meta:Int,buttons:Int,xp:Float,yp:Float,device:Int,edge:Int,source:Int,flags:Int)=MotionEvent(down,at,action,coords[0].x,coords[0].y).also{it.source=source}
    }
}
object ViewConfiguration {fun getLongPressTimeout()=500;fun getDoubleTapTimeout()=300}
class GestureDetector(context:Context,val listener:SimpleOnGestureListener) {
    open class SimpleOnGestureListener {
        open fun onDown(e:MotionEvent)=false
        open fun onSingleTapUp(e:MotionEvent)=false
        open fun onScroll(e1:MotionEvent?,e2:MotionEvent,distanceX:Float,distanceY:Float)=false
    }
    var held:MotionEvent?=null
    var cancels=0
    init {val failure=failNext;failNext=null;if(failure!=null)throw failure;instances.add(this)}
    fun onTouchEvent(event:MotionEvent):Boolean=when(event.action){
        MotionEvent.ACTION_DOWN->{held=event;listener.onDown(event)}
        MotionEvent.ACTION_UP->{val old=held;held=null;if(old!=null)listener.onSingleTapUp(event)else false}
        MotionEvent.ACTION_CANCEL->{held=null;cancels++;onCancel?.invoke(this);true}
        MotionEvent.ACTION_MOVE->if(held!=null)listener.onScroll(held,event,0f,10f)else false
        else->false
    }
    companion object {val instances=mutableListOf<GestureDetector>();var onCancel:((GestureDetector)->Unit)?=null;var failNext:RuntimeException?=null}
}
