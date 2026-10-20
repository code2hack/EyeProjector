package com.code2hack.eyeprojector.directhost;

// Only EyeProjector operations. No command, executable or caller-chosen path API.
interface IHostBootstrap {
    String status() = 0;
    String start(in byte[] identity, in byte[] password, in @nullable byte[] legacyPeer, in byte[] endpoint, in @nullable byte[] pairedPeer) = 1;
    String stop() = 2;
    void destroy() = 16777114;
}
