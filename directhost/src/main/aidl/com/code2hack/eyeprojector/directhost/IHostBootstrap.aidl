package com.code2hack.eyeprojector.directhost;

// Only EyeProjector operations. No command, executable or caller-chosen path API.
interface IHostBootstrap {
    String status() = 0;
    String start(in byte[] identity, in byte[] password, in @nullable byte[] legacyPeer, in byte[] endpoint, in @nullable byte[] pairedPeer, String sessionEpoch) = 1;
    String stop() = 2;
    byte[] requestScale(long requestId, double scale) = 3;
    byte[] scaleState() = 4;
    byte[] requestPanelOff(long requestId, String expectedOwnerEpoch, String expectedProjectionToken) = 5;
    byte[] panelState(String expectedOwnerEpoch, String expectedProjectionToken) = 6;
    void destroy() = 16777114;
}
