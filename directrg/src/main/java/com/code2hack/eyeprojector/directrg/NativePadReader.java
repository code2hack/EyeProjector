package com.code2hack.eyeprojector.directrg;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shell-only app_process entrypoint. Reads evdev without grabbing or changing the input device. */
public final class NativePadReader {
    public static final String SOCKET_NAME = "eyeprojector-native-pad-99";

    public static void main(String[] args) throws Exception {
        String device = args.length > 0 ? args[0] : "/dev/input/event1";
        String name = args.length > 1 ? args[1] : SOCKET_NAME;
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            Process events = new ProcessBuilder("/system/bin/getevent", "-lt", device)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            System.err.println("NativePadReader pid=" + android.os.Process.myPid()
                    + " device=" + device + " socket=" + name);
            // Socket EOF must stop getevent even when no further pad events arrive.
            Thread closed = new Thread(() -> {
                try {
                    while (socket.getInputStream().read() != -1) { }
                } catch (IOException ignored) {
                } finally {
                    events.destroy();
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }, "native-pad-reader-eof");
            closed.setDaemon(true);
            closed.start();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    events.getInputStream(), StandardCharsets.US_ASCII))) {
                OutputStream output = socket.getOutputStream();
                long sequence = 0;
                boolean pressed = false;
                String line;
                while ((line = input.readLine()) != null) {
                    String[] fields = line.trim().split("\\s+");
                    int end = fields.length;
                    if (end < 3) continue;
                    String type = fields[end - 3];
                    String code = fields[end - 2];
                    String value = fields[end - 1];
                    if (!(type.equals("EV_KEY") || type.equals("0001"))
                            || !(code.equals("KEY_PROG1") || code.equals("0094"))) continue;
                    if (value.equals("DOWN") || value.equals("00000001")) {
                        if (pressed) continue;
                        pressed = true;
                        sequence++;
                        send(output, "DOWN", sequence);
                    } else if (value.equals("UP") || value.equals("00000000")) {
                        if (!pressed) continue;
                        pressed = false;
                        send(output, "UP", sequence);
                    }
                    // Kernel repeat values never create another contact or a release.
                }
            } finally {
                events.destroy();
            }
        }
    }

    private static void send(OutputStream output, String phase, long press) throws IOException {
        output.write((phase + " " + press + "\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }
}
