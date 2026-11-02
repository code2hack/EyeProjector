package com.code2hack.eyeprojector.directhost.anchor.execution;
import com.code2hack.eyeprojector.directhost.anchor.execution.IAnchorRawControl;
// One captured original Phone Session's central gate. No command/path or held-label authority.
interface IAnchorControlSession {
    int admitUserControl(boolean recognitionOnly);
    @nullable IAnchorRawControl captureRawControl(boolean anchorOperation, out int[] disposition);
}
