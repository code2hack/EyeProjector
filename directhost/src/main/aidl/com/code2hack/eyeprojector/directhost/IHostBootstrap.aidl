package com.code2hack.eyeprojector.directhost;

// Only EyeProjector operations. No command, executable or caller-chosen path API.
interface IHostBootstrap {
    String status() = 0;
    String start(in byte[] identity, in byte[] password, in @nullable byte[] legacyPeer, in byte[] endpoint, in @nullable byte[] pairedPeer, String sessionEpoch) = 1;
    String stop() = 2;
    byte[] requestScale(long requestId, double scale, String expectedOwnerEpoch, String expectedProjectionToken) = 3;
    byte[] scaleState(String expectedOwnerEpoch, String expectedProjectionToken) = 4;
    byte[] requestPanelOff(long requestId, String expectedOwnerEpoch, String expectedProjectionToken) = 5;
    byte[] panelState(String expectedOwnerEpoch, String expectedProjectionToken) = 6;
    byte[] launchApp(long requestId, String packageName, String expectedOwnerEpoch, String expectedProjectionToken) = 7;
    byte[] projectionDisplayState(String expectedOwnerEpoch, String expectedProjectionToken) = 8;
    oneway void phoneHidEdge(long routeId, in byte[] typedEdge, String expectedOwnerEpoch, String expectedProjectionToken) = 9;
    void destroy() = 16777114;
}
