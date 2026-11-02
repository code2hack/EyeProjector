package com.code2hack.eyeprojector.directrg;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;

/** Shell-only app_process entrypoint. Reads evdev without grabbing or changing the input device. */
public final class NativePadReader {
    public static final String SOCKET_NAME = "eyeprojector-native-pad-99";
    public static final String POWER_SOCKET_NAME = "eyeprojector-native-power-99";

    public static void main(String[] args) throws Exception {
        String device = args.length > 0 ? args[0] : "/dev/input/event1";
        String name = args.length > 1 ? args[1] : SOCKET_NAME;
        retainPowerOwner();
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            Process events = new ProcessBuilder("/system/bin/getevent", "-lt", device)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            OutputStream output = socket.getOutputStream();
            // The observed Function node is supplied separately from the pad node. Its real
            // UP remains available here even when OEM policy suppresses the long-press UP broadcast.
            Process functionEvents = args.length > 2 ? startFunctionReader(args[2], output) : null;
            System.err.println("NativePadReader pid=" + android.os.Process.myPid()
                    + " device=" + device + " socket=" + name);
            // The pad connection owns only its raw reader; the separate power owner survives it.
            Thread closed = new Thread(() -> {
                try {
                    while (socket.getInputStream().read() != -1) { }
                } catch (IOException ignored) {
                } finally {
                    events.destroy();
                    if (functionEvents != null) functionEvents.destroy();
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }, "native-pad-reader-eof");
            closed.setDaemon(true);
            closed.start();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    events.getInputStream(), StandardCharsets.US_ASCII))) {
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
                if (functionEvents != null) functionEvents.destroy();
            }
        } catch (IOException error) {
            // Activity exit/EOF ends raw contact resources without terminating the power owner.
            System.err.println("NativePadReader pad reader closed: " + error);
        }
    }

    private static Process startFunctionReader(String device, OutputStream output) {
        try {
            Process events = new ProcessBuilder("/system/bin/getevent", "-lt", device)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(
                        events.getInputStream(), StandardCharsets.US_ASCII))) {
                    sendLine(output, "FUNCTION_SOURCE");
                    long sequence = 0;
                    boolean pressed = false;
                    String line;
                    while ((line = input.readLine()) != null) {
                        String[] fields = line.trim().split("\\s+");
                        int end = fields.length;
                        if (end < 3) continue;
                        String type = fields[end - 3], code = fields[end - 2], value = fields[end - 1];
                        // Actual qpnp_pon scan139 is KEY_MENU in evdev and SPRITE_FUNCTION
                        // in this OEM keylayout. It is not Android KEYCODE_MENU.
                        if (!(type.equals("EV_KEY") || type.equals("0001"))
                                || !(code.equals("KEY_MENU") || code.equals("008b"))) continue;
                        long at = kernelUptimeMs(line);
                        if (at < 0) continue;
                        if (value.equals("DOWN") || value.equals("00000001")) {
                            if (pressed) continue;
                            pressed = true;
                            sendLine(output, "FUNCTION_DOWN " + (++sequence) + " " + at);
                        } else if (value.equals("UP") || value.equals("00000000")) {
                            if (!pressed) continue;
                            pressed = false;
                            sendLine(output, "FUNCTION_UP " + sequence + " " + at);
                        }
                    }
                } catch (IOException error) {
                    System.err.println("NativePadReader Function reader closed: " + error);
                } finally {
                    events.destroy();
                    // EOF retires the original source; it is never a physical UP.
                    try { sendLine(output, "FUNCTION_LOST"); } catch (IOException ignored) { }
                }
            }, "native-function-reader");
            reader.setDaemon(true);
            reader.start();
            System.err.println("NativePadReader Function device=" + device);
            return events;
        } catch (IOException error) {
            System.err.println("NativePadReader Function reader unavailable: " + error);
            return null;
        }
    }

    private static long kernelUptimeMs(String line) {
        int begin = line.indexOf('['), end = line.indexOf(']');
        if (begin < 0 || end <= begin) return -1;
        try { return new BigDecimal(line.substring(begin + 1, end).trim()).movePointRight(3).longValue(); }
        catch (NumberFormatException error) { return -1; }
    }

    /** One blocking owner in this same shell process; Activity instances do not own its lifetime. */
    private static void retainPowerOwner() throws IOException {
        LocalServerSocket server = new LocalServerSocket(POWER_SOCKET_NAME);
        Thread owner = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    LocalSocket request = server.accept();
                    try (LocalSocket connection = request;
                         BufferedReader commands = new BufferedReader(new InputStreamReader(
                                 connection.getInputStream(), StandardCharsets.US_ASCII))) {
                        if ("SLEEP".equals(commands.readLine())) {
                            Process sleep = new ProcessBuilder("/system/bin/input", "keyevent", "223")
                                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                            try {
                                System.err.println("NativePadReader KEYCODE_SLEEP exit=" + sleep.waitFor());
                            } finally {
                                sleep.destroy();
                            }
                        }
                    } catch (IOException error) {
                        System.err.println("NativePadReader power request closed: " + error);
                    }
                }
            } catch (IOException error) {
                System.err.println("NativePadReader power listener closed: " + error);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                try { server.close(); } catch (IOException ignored) { }
            }
        }, "native-pad-power");
        owner.setDaemon(false);
        try {
            owner.start();
        } catch (RuntimeException | Error error) {
            try { server.close(); } catch (IOException ignored) { }
            throw error;
        }
        System.err.println("NativePadReader retained power pid=" + android.os.Process.myPid()
                + " socket=" + POWER_SOCKET_NAME);
    }

    private static void send(OutputStream output, String phase, long press) throws IOException {
        sendLine(output, phase + " " + press);
    }

    private static void sendLine(OutputStream output, String line) throws IOException {
        synchronized (output) {
            output.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
    }
}
