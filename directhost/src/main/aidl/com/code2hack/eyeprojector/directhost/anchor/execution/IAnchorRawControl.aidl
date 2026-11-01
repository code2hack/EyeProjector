package com.code2hack.eyeprojector.directhost.anchor.execution;
// One represented native press, captured before its app queues; never resolves a later Session/Hold.
interface IAnchorRawControl {
    int admitUserControl();
    oneway void edge(long routeId, in byte[] typedEdge, String regionId, long word);
}
