package com.code2hack.eyeprojector.directhost.activation
import com.code2hack.eyeprojector.directhost.PhoneSessionController
/** Explicit pre-transport local port for retained old composition/goal controls.
 * No descriptor/native claim. Production capture is tested in producer --transport instead. */
internal fun historicalHidPort(sessions:PhoneSessionController)=object:PhoneHidActivation {
 override fun needsWake()=sessions.needsActivation()
 override fun request(cause:ActivationCause){sessions.requestActivation(cause)}
 override fun capture():PhoneHidCapture {
  val disposition=if(needsWake())PhoneHidDisposition.UNKNOWN else PhoneHidDisposition.USABLE
  return PhoneHidCapture(disposition,HidWakeRequest(disposition,sessions.captureHidAuthority()),sessions::capturePhoneHidRoute)
 }
}
