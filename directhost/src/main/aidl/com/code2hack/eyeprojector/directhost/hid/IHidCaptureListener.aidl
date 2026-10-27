package com.code2hack.eyeprojector.directhost.hid;

oneway interface IHidCaptureListener {
    void onState(String stateJson) = 0;
    void onInput(String inputJson) = 1;
}
