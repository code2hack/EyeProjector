package com.code2hack.eyeprojector.directhost;
import com.code2hack.eyeprojector.directhost.IPhoneInputSession;
interface IPhoneModeOwner {
    void onSession(String ownerEpoch, String projectionToken, IPhoneInputSession session);
    @nullable byte[] decide(String expectedOwnerEpoch, String expectedProjectionToken, @nullable String packageName, int operation, double x, double y);
}
