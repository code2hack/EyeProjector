package com.code2hack.eyeprojector.directhost.anchor.execution;
import com.code2hack.eyeprojector.directhost.anchor.execution.IAnchorSession;
import com.code2hack.eyeprojector.directhost.anchor.execution.IAnchorControlSession;
import com.code2hack.eyeprojector.directhost.anchor.execution.IAnchorUserControl;

interface IAnchorAppBridge {
    oneway void onIntent(long transactionId, in byte[] functionIntent, IAnchorSession original);
    oneway void onRegistration(in byte[] message, IAnchorSession original);
    void onSession(String ownerEpoch, String projectionToken, IAnchorControlSession original);
    oneway void onControl(IAnchorUserControl original);
}
