package com.code2hack.eyeprojector.directhost;
// Captured original Session; never resolves a replacement projection.
interface IPhoneInputSession {
    boolean active();
    oneway void edge(long routeId, in byte[] typedEdge, String regionId, long word);
}
