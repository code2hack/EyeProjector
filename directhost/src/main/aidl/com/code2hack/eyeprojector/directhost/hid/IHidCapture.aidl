package com.code2hack.eyeprojector.directhost.hid;

import com.code2hack.eyeprojector.directhost.hid.IHidCaptureListener;

interface IHidCapture {
    String inventory() = 0;
    String state() = 1;
    void attach(IHidCaptureListener listener) = 2;
    String pin(String deviceKey) = 3;
    String unpin() = 4;
    void destroy() = 16777114;
}
