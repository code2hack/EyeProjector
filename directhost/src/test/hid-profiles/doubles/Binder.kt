package android.os
interface IBinder { fun linkToDeath(recipient: () -> Unit, flags: Int) = Unit }
