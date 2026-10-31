package com.code2hack.eyeprojector.directhost.anchor.execution;
interface IAnchorSession {
    @nullable byte[] source();
    boolean reconcile(in byte[] settingContext);
    int click(in byte[] settingContext, in byte[] resolvedTarget);
    void retire();
    void send(in byte[] typedMessage);
    @nullable byte[] heldSetting();
    int releaseHeld(in byte[] expectedSetting, boolean settingCurrent);
    int hold(in byte[] settingContext, in byte[] resolvedTarget);
}
