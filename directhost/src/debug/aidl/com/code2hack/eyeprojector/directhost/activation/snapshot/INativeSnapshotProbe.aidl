package com.code2hack.eyeprojector.directhost.activation.snapshot;
import com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotDescriptor;
/** Debug-only owned region. No production IHostBootstrap IDs or product input operation. */
interface INativeSnapshotProbe {
    NativeSnapshotDescriptor descriptor() = 0;
    long publishMode(int mode) = 1;
    long retireRegion() = 2;
    void closeRegion() = 3;
    int pid() = 4;
    int uid() = 5;
    int bitness() = 6;
    void destroy() = 16777114;
}
