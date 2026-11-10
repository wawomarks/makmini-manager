package org.makmini.manager;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root command transport.
 *
 * <p>Three paths, tried in order:
 *
 * <ol>
 *   <li><b>minisud</b> on {@code /dev/minisud.sock}. Requests use the
 *       {@code MSD1 'T'} framing so stdout/stderr are captured and returned
 *       even for app processes that have no controlling terminal.</li>
 *   <li><b>GhostLock channel</b> ({@code mpnl}). The helper polls
 *       {@code /data/local/tmp/rcmd} and writes the result to
 *       {@code /data/local/tmp/rout}; both files are world-writable, so the
 *       app can drive it without any su binary or daemon. This is the path
 *       that survives "minisud is dead" and boot-time attempts.</li>
 *   <li><b>su binary</b> (GhostLock installs {@code /odm/bin/su}).</li>
 * </ol>
 *
 * <p>{@link #warm(String...)} must be called before a root-side command reads a
 * file on {@code /data}: kernel root has no fscrypt key, so cold inodes fail
 * with ENOKEY. Reading the file from the app fills the page cache.
 */
public final class RootClient {

    public static final String SOCK = "/dev/minisud.sock";

    /** Daemon payload deployed by the kit. */
    public static final String DAEMON = "/data/local/tmp/.gl/bin/minisud";

    /** GhostLock root helper command/response files (mode 0666). */
    public static final String CHAN_CMD = "/data/local/tmp/rcmd";
    public static final String CHAN_OUT = "/data/local/tmp/rout";

    private static final String[] SU_BINS = {
            "/odm/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/su/bin/su",
    };

    /** Files the root side needs before it can start the daemon. */
    private static final String[] KIT_FILES = {
            "/data/local/tmp/defex/defex",
            "/data/local/tmp/defex/defex.sh",
            "/data/local/tmp/.gl/bin/minisud",
            "/data/local/tmp/.gl/bin/makmini_boot.sh",
    };

    private static final int MAX_OUTPUT = 1024 * 1024;
    private static final long CHAN_RETRY_MS = 15000;

    private static final Object CHAN_LOCK = new Object();
    private static int chanState;          // 0 unknown, 1 up, -1 down
    private static long chanDownAt;

    private RootClient() {
    }

    /** Result of a root command. */
    public static final class Result {
        public final int rc;
        public final String out;
        /** Transport that answered: "minisud", "mpnl" or "su". */
        public final String via;

        Result(int rc, String out, String via) {
            this.rc = rc;
            this.out = out == null ? "" : out;
            this.via = via;
        }

        public boolean ok() {
            return rc == 0;
        }

        @Override
        public String toString() {
            String body = out.trim();
            String suffix = "[rc=" + rc + " via " + via + "]";
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
     * Runs {@code command} as uid 0. Never throws: failures come back as a
     * non-zero rc with a message.
     */
    public static Result run(String command, int timeoutMs) {
        Result r = viaDaemon(command, timeoutMs);
        if (r != null) {
            return r;
        }
        r = viaChannel(command, timeoutMs);
        if (r != null) {
            return r;
        }
        return viaSu(command, timeoutMs);
    }

    /**
     * Reads files so their pages land in the page cache. Root can then exec or
     * copy them even though it has no fscrypt key. Returns the file count read.
     */
    public static int warm(String... paths) {
        byte[] buf = new byte[64 * 1024];
        int files = 0;
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            File f = new File(path);
            if (f.isDirectory()) {
                files += warmTree(f, buf, 4000);
                continue;
            }
            if (readFully(f, buf)) {
                files++;
            }
        }
        return files;
    }

    /** Warms the binaries needed to launch the daemon from root. */
    public static int warmKit() {
        return warm(KIT_FILES);
    }

    /** Forces the next call to re-probe the GhostLock channel. */
    public static void resetChannel() {
        synchronized (CHAN_LOCK) {
            chanState = 0;
            chanDownAt = 0;
        }
    }

    /**
     * Shell snippet that starts minisud from a root context.
     *
     * <p>It must not call {@code defex.sh}: that script re-enters root through
     * the {@code mpnl} channel, so running it from a channel command deadlocks
     * the single command slot forever. A root caller can do the safeplace bind
     * itself, then exec the daemon.
     */
    public static String startDaemonCommand() {
        return "[ -S " + SOCK + " ] && { echo 'minisud already running'; exit 0; }; "
                + "B=" + DAEMON + "; S=/system/bin/fsdbg; "
                + "[ -x \"$B\" ] || { echo \"missing $B\"; exit 127; }; "
                + "mount --bind \"$B\" \"$S\" || { echo 'bind failed'; exit 4; }; "
                + "nohup \"$S\" </dev/null >/dev/null 2>&1 & "
                + "i=0; while [ $i -lt 40 ]; do [ -S " + SOCK + " ] && break; "
                + "sleep 0.25; i=$((i+1)); done; "
                + "echo \"pid=$(cat /dev/minisud.pid 2>/dev/null)\"; "
                + "[ -S " + SOCK + " ] && echo 'minisud is up' || echo 'minisud did not start'";
    }

    // ------------------------------------------------------------- transports

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
            return new Result(rc, stripMarker(raw), "minisud");
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * The GhostLock file channel. Serialised because the helper has a single
     * command slot; the state cache keeps status refreshes fast when root is
     * not armed.
     */
    private static Result viaChannel(String command, int timeoutMs) {
        synchronized (CHAN_LOCK) {
            long now = System.currentTimeMillis();
            if (chanState < 0 && now - chanDownAt < CHAN_RETRY_MS) {
                return null;
            }
            if (chanState == 0) {
                if (!new File(CHAN_CMD).canWrite() || !new File(CHAN_OUT).canWrite()) {
                    chanState = -1;
                    chanDownAt = now;
                    return null;
                }
                Result probe = channelSend("true", 3000);
                if (probe == null || probe.rc == 124) {
                    chanState = -1;
                    chanDownAt = now;
                    return null;
                }
                chanState = 1;
            }
            return channelSend(command, timeoutMs);
        }
    }

    private static Result channelSend(String command, int timeoutMs) {
        File cmd = new File(CHAN_CMD);
        File out = new File(CHAN_OUT);
        try {
            try (FileOutputStream ignore = new FileOutputStream(out, false)) {
                // Clear the previous result.
            }
            try (FileOutputStream fos = new FileOutputStream(cmd, false)) {
                fos.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (IOException e) {
            return null;
        }

        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        String raw = "";
        while (System.currentTimeMillis() < deadline) {
            if (out.length() > 0) {
                sleep(200);      // let the helper finish appending the rc line
                raw = readText(out);
                break;
            }
            sleep(100);
        }
        if (raw.isEmpty()) {
            return new Result(124, "[timeout waiting for the GhostLock channel]", "mpnl");
        }
        int rc = parseChanRc(raw);
        return new Result(rc, stripChanRc(raw), "mpnl");
    }

    private static Result viaSu(String command, int timeoutMs) {
        String su = suBinary();
        if (su == null) {
            return new Result(127,
                    "no root transport: " + SOCK + " unreachable, GhostLock channel idle "
                            + "and no su binary found.\n"
                            + "Arm root first (adb shell sh /data/local/tmp/glroot.sh true).",
                    "su");
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
                return new Result(124, out + "\n[timeout after " + timeoutMs + " ms]", "su");
            }
            return new Result(p.exitValue(), out, "su");
        } catch (IOException e) {
            return new Result(126, "failed to run " + su + ": " + e.getMessage(), "su");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int warmTree(File dir, byte[] buf, int budget) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        int files = 0;
        for (File k : kids) {
            if (files >= budget) {
                break;
            }
            if (k.isDirectory()) {
                files += warmTree(k, buf, budget - files);
            } else if (readFully(k, buf)) {
                files++;
            }
        }
        return files;
    }

    private static boolean readFully(File f, byte[] buf) {
        try (InputStream in = new FileInputStream(f)) {
            while (in.read(buf) > 0) {
                // Pages only: the data is intentionally discarded.
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_OUTPUT) {
                    break;
                }
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
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

    private static int parseChanRc(String raw) {
        int idx = raw.lastIndexOf("[rc=");
        if (idx < 0) {
            return 0;
        }
        int end = idx + 4;
        StringBuilder sb = new StringBuilder();
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            sb.append(raw.charAt(end++));
        }
        if (sb.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
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

    private static String stripChanRc(String raw) {
        int idx = raw.lastIndexOf("[rc=");
        String body = idx < 0 ? raw : raw.substring(0, idx);
        while (body.endsWith("\n") || body.endsWith("\r")) {
            body = body.substring(0, body.length() - 1);
        }
        return body;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
