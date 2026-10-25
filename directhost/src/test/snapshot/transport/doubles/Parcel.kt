package android.os

/** Explicit Parcel/FD boundary. No Android Binder or descriptor transfer is executed on this host. */
interface Parcelable {
 fun describeContents():Int
 fun writeToParcel(dest:Parcel,flags:Int)
 interface Creator<T>{fun createFromParcel(source:Parcel):T;fun newArray(size:Int):Array<T?>}
 companion object {const val CONTENTS_FILE_DESCRIPTOR=1;const val PARCELABLE_WRITE_RETURN_VALUE=1}
}
class Parcel private constructor(){
 private val values=mutableListOf<Any?>();private var position=0
 fun writeString(value:String?){values.add(value)}
 fun readString()=values[position++] as String?
 fun writeInt(value:Int){values.add(value)}
 fun readInt()=values[position++] as Int
 fun writeBoundary(value:Any){values.add(value)}
 fun readBoundary()=values[position++]!!
 fun setDataPosition(value:Int){position=value}
 fun recycle()=Unit
 companion object {fun obtain()=Parcel()}
}
