package com.code2hack.eyeprojector.protocol;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;

/** A separate JVM owns this process-wide factory. No real UDP socket, port or traffic. */
public final class LanDiscoverySocketProbe {
    private static final String OWN = "1".repeat(64), OTHER = "2".repeat(64);
    private static final String PRIVATE_SENTINEL = "PRIVATE_PACKET_SENTINEL_0123";
    private static final AtomicInteger receives = new AtomicInteger(), failures = new AtomicInteger(), peers = new AtomicInteger();
    private static final CountDownLatch found = new CountDownLatch(1);
    private static String mode;

    public static void main(String[] args) throws Exception {
        mode = args[0];
        DatagramSocket.setDatagramSocketImplFactory(Model::new);
        LanDiscoveryDiagnostics diagnostics = new LanDiscoveryDiagnostics();
        LanDiscovery discovery = new LanDiscovery(OWN, PeerRole.PHONE, 28001,
            peer -> { peers.incrementAndGet(); found.countDown(); return Unit.INSTANCE; }, diagnostics,
            () -> { failures.incrementAndGet(); return Unit.INSTANCE; });
        try {
            discovery.start();
            if (mode.equals("receive-fails")) {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (count(diagnostics, "receiveLoopStopped") == 0 && System.nanoTime() < until) Thread.sleep(1);
                require(count(diagnostics, "receiveLoopStopped") == 1, "Receive loop did not stop");
                require(receives.get() == 1 && peers.get() == 0 && failures.get() == 1, "Fatal receive semantics changed");
                require(count(diagnostics, "receiveFatalStops") == 1, "Missing fatal receive observation");
                require(diagnostics.snapshot().getErrorClasses().getOrDefault("RECEIVE:SocketException", 0L) == 1, "Receive error class missing");
            } else {
                require(found.await(3, TimeUnit.SECONDS), "Valid peer suppressed after earlier input/reply failure");
                require(peers.get() == 1, "Unexpected eligible peer count");
                if (mode.equals("reply-fails")) {
                    require(failures.get() == 1 && count(diagnostics, "queryRepliesFailed") == 1, "Reply error not reported");
                    require(count(diagnostics, "receiveFatalStops") == 0, "Reply error terminated receive loop");
                    require(diagnostics.snapshot().getErrorClasses().getOrDefault("SEND_QUERY_REPLY:SocketException", 0L) == 1, "Reply class missing");
                } else {
                    require(count(diagnostics, "rejectedAnnouncements") == 1, "Malformed input not counted");
                    require(count(diagnostics, "selfAnnouncements") == 1, "Own identity filter not counted");
                    require(count(diagnostics, "sameRoleAnnouncements") == 1, "Role filter not counted");
                    require(count(diagnostics, "eligibleAnnouncements") == 1, "Eligible input not counted");
                }
            }
            require(diagnostics.snapshot().getSocket().getBound(), "Missing bound facts");
            require(diagnostics.snapshot().getSocket().getPort() == 27184, "Wrong bound port observation");
            require(diagnostics.snapshot().getSocket().getBroadcast(), "Broadcast option observation missing");
        } finally { discovery.close(); }
        String report = diagnostics.snapshot().toJson();
        require(!report.contains(OWN) && !report.contains(OTHER) && !report.contains(PRIVATE_SENTINEL), "Private packet data escaped into report");
        require(!report.contains("HOST_MODEL_PRIVATE_EXCEPTION"), "Exception message escaped into report");
        System.out.println("PASS " + mode);
    }
    private static long count(LanDiscoveryDiagnostics d, String key) { return d.snapshot().getCounters().getOrDefault(key, 0L); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Model extends DatagramSocketImpl {
        private boolean closed;
        private int delivered;
        private final Map<Integer, Object> options = new HashMap<>();
        protected void create() { fd = new FileDescriptor(); }
        protected void bind(int port, InetAddress address) { localPort = port; }
        protected void send(DatagramPacket p) throws IOException {
            if (mode.equals("reply-fails") && p.getAddress().getHostAddress().equals("127.0.0.2"))
                throw new SocketException("HOST_MODEL_PRIVATE_EXCEPTION");
        }
        protected synchronized void receive(DatagramPacket p) throws IOException {
            receives.incrementAndGet();
            if (mode.equals("receive-fails")) throw new SocketException("HOST_MODEL_PRIVATE_EXCEPTION");
            List<String> input = mode.equals("reply-fails") ? Arrays.asList("EPD1?", announcement("GLASSES", OTHER)) :
                Arrays.asList(PRIVATE_SENTINEL, announcement("GLASSES", OWN), announcement("PHONE", OTHER), announcement("GLASSES", OTHER));
            if (delivered < input.size()) {
                byte[] b = input.get(delivered++).getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(b, 0, p.getData(), p.getOffset(), b.length);
                p.setLength(b.length); p.setAddress(InetAddress.getByName("127.0.0.2")); p.setPort(27184); return;
            }
            while (!closed) try { wait(); } catch (InterruptedException e) { throw new SocketException("HOST_MODEL_INTERRUPTED"); }
            throw new SocketException("HOST_MODEL_CLOSED");
        }
        private static String announcement(String role, String id) { return "EPD1 " + role + " " + id + " 28002 27183"; }
        protected int peek(InetAddress a) { return 0; }
        protected int peekData(DatagramPacket p) { return 0; }
        protected void setTTL(byte v) {}
        protected byte getTTL() { return 0; }
        protected void setTimeToLive(int v) {}
        protected int getTimeToLive() { return 0; }
        protected void join(InetAddress a) {}
        protected void leave(InetAddress a) {}
        protected void joinGroup(SocketAddress a, NetworkInterface n) {}
        protected void leaveGroup(SocketAddress a, NetworkInterface n) {}
        protected synchronized void close() { closed = true; notifyAll(); }
        public void setOption(int key, Object value) { options.put(key, value); }
        public Object getOption(int key) throws SocketException {
            if (key == SO_BINDADDR) return InetAddress.getLoopbackAddress();
            return options.getOrDefault(key, Boolean.FALSE);
        }
    }
}
