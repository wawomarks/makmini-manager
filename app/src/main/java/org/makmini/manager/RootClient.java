package org.makmini.manager;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root command transport.
 *
 * <p>Primary path: the minisud daemon on {@code /dev/minisud.sock}. Requests use
 * the {@code MSD1 'T'} framing so stdout/stderr are captured and returned even
 * for app processes that have no controlling terminal.
 *
 * <p>Fallback: a {@code su} binary (GhostLock installs {@code /odm/bin/su}).
 * This is what lets the manager start the daemon or deploy the kit before the
 * socket exists.
 */
public final class RootClient {

    public static final String SOCK = "/dev/minisud.sock";

    private static final String[] SU_BINS = {
            "/odm/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/su/bin/su",
    };

    private static final int MAX_OUTPUT = 1024 * 1024;

    private RootClient() {
    }

    /** Result of a root command. */
    public static final class Result {
        public final int rc;
        public final String out;
        public final boolean viaDaemon;

        Result(int rc, String out, boolean viaDaemon) {
            this.rc = rc;
            this.out = out == null ? "" : out;
            this.viaDaemon = viaDaemon;
        }

        public boolean ok() {
            return rc == 0;
        }

        @Override
        public String toString() {
            String body = out.trim();
            String suffix = "[rc=" + rc + (viaDaemon ? " via minisud" : " via su") + "]";
            return body.isEmpty() ? suffix : body + "\n" + suffix;
        }
    }

    /** True when the daemon socket accepts a connection. */
    public static boolean daemonAlive() {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /** True when some su binary is present and executable. */
    public static String suBinary() {
        for (String path : SU_BINS) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                return path;
            }
        }
        return null;
    }

    /**
     * Runs {@code command} as uid 0. Tries the daemon first, then a su binary.
     * Never throws: failures come back as a non-zero rc with a message.
     */
    public static Result run(String command, int timeoutMs) {
        Result r = viaDaemon(command, timeoutMs);
        if (r != null) {
            return r;
        }
        return viaSu(command, timeoutMs);
    }

    private static Result viaDaemon(String command, int timeoutMs) {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(Math.max(1000, timeoutMs));

            byte[] payload = command.getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[9];
            header[0] = 'M';
            header[1] = 'S';
            header[2] = 'D';
            header[3] = '1';
            header[4] = 'T';
            int n = payload.length;
            header[5] = (byte) ((n >>> 24) & 0xff);
            header[6] = (byte) ((n >>> 16) & 0xff);
            header[7] = (byte) ((n >>> 8) & 0xff);
            header[8] = (byte) (n & 0xff);

            OutputStream os = socket.getOutputStream();
            os.write(header);
            os.write(payload);
            os.flush();
            socket.shutdownOutput();

            String raw = readAll(socket.getInputStream());
            int rc = parseRc(raw);
            return new Result(rc, stripMarker(raw), true);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(socket);
        }
    }

    private static Result viaSu(String command, int timeoutMs) {
        String su = suBinary();
        if (su == null) {
            return new Result(127,
                    "no root transport: " + SOCK + " unreachable and no su binary found.\n"
                            + "Arm root first (adb shell sh /data/local/tmp/glroot.sh true).",
                    false);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(su, "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            String out = readAll(p.getInputStream());

            boolean done;
            try {
                done = p.waitFor(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                done = false;
            }
            if (!done) {
                p.destroyForcibly();
                return new Result(124, out + "\n[timeout after " + timeoutMs + " ms]", false);
            }
            return new Result(p.exitValue(), out, false);
        } catch (IOException e) {
            return new Result(126, "failed to run " + su + ": " + e.getMessage(), false);
        }
    }

    private static String readAll(InputStream in) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_OUTPUT) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Timeout or peer reset: return whatever arrived.
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseRc(String raw) {
        int idx = raw.lastIndexOf("__RC__:");
        if (idx < 0) {
            return -1;
        }
        int end = idx + 7;
        StringBuilder sb = new StringBuilder();
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            sb.append(raw.charAt(end++));
        }
        if (sb.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String stripMarker(String raw) {
        int idx = raw.lastIndexOf("__RC__:");
        if (idx < 0) {
            return raw;
        }
        String body = raw.substring(0, idx);
        while (body.endsWith("\n") || body.endsWith("\r")) {
            body = body.substring(0, body.length() - 1);
        }
        return body;
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
