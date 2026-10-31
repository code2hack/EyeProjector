package com.code2hack.eyeprojector.directhost.anchor.execution;
import com.code2hack.eyeprojector.directhost.anchor.execution.IAnchorSession;
interface IAnchorAppBridge {
    oneway void onIntent(long transactionId, in byte[] functionIntent, IAnchorSession original);
    oneway void onRegistration(in byte[] message, IAnchorSession original);
}
