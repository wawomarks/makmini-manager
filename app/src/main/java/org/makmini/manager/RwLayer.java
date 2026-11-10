package org.makmini.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RW layers without glm/gl_fix.
 *
 * <p>Each layer is a tmpfs copy bind-mounted over a read-only path:
 *
 * <pre>
 *   /dev/makmini_rw/&lt;id&gt;    live upper layer (tmpfs, survives until reboot)
 *   /dev/makmini_stage/     staging shared with the app (root has no fscrypt key)
 *   &lt;app files&gt;/rw/&lt;id&gt;.tar  persistent snapshot (tar keeps modes and links)
 * </pre>
 *
 * <p>Root can read and write {@code /dev} (tmpfs) but cannot create inodes on
 * {@code /data} or FUSE {@code /sdcard}, so the app copies snapshots between the
 * staging directory and its own private storage. Every root command runs
 * through {@link RootClient} (minisud, GhostLock channel or su).
 */
public final class RwLayer {

    public static final String LIVE = "/dev/makmini_rw";
    public static final String STAGE = "/dev/makmini_stage";

    private static final String PREFS = "rw";
    private static final String KEY_PATHS = "paths";

    private RwLayer() {
    }

    // ------------------------------------------------------------------- api

    /** Adds (or re-applies) a layer for {@code rawPath}. Blocking. */
    public static String add(Context ctx, String rawPath) {
        String path = normalize(rawPath);
        if (path == null) {
            return "rw add: enter an absolute path (not /)";
        }
        RootClient.Result exists = RootClient.run(
                "[ -e " + sq(path) + " ] && echo yes || echo no", 15000);
        if (!exists.out.contains("yes")) {
            return "rw add: not found: " + path;
        }

        List<String> paths = paths(ctx);
        if (!paths.contains(path)) {
            paths.add(path);
            store(ctx, paths);
        }
        return mountLayer(ctx, path);
    }

    /** Snapshots every live layer into the app's private mirror. Blocking. */
    public static String save(Context ctx) {
        List<String> paths = paths(ctx);
        if (paths.isEmpty()) {
            return "rw save: no layers";
        }
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            String id = id(path);
            RootClient.Result r = RootClient.run(
                    "L=" + LIVE + "/" + id + "; T=" + STAGE + "/" + id + ".tar; "
                            + "mkdir -p " + STAGE + "; chmod 777 " + STAGE + "; "
                            + "[ -d \"$L\" ] || { echo 'no live layer'; exit 3; }; "
                            + "tar -cf \"$T\" -C \"$L\" . && chmod 644 \"$T\" && echo 'tar ok'",
                    180000);
            if (!r.ok() || !r.out.contains("tar ok")) {
                out.append(path).append(": ").append(r.toString()).append('\n');
                continue;
            }
            File dst = new File(mirrorDir(ctx), id + ".tar");
            if (pull(new File(STAGE + "/" + id + ".tar"), dst)) {
                out.append(path).append(": saved ").append(dst.length()).append(" bytes\n");
            } else {
                out.append(path).append(": snapshot written but copy-out failed\n");
            }
        }
        return out.toString().trim();
    }

    /** Shows each configured layer and its mirror. Blocking. */
    public static String list(Context ctx) {
        List<String> paths = paths(ctx);
        if (paths.isEmpty()) {
            return "(no rw layers)";
        }

        StringBuilder cmd = new StringBuilder();
        for (String path : paths) {
            String id = id(path);
            cmd.append("P=").append(sq(path)).append("; ")
                    .append("M=").append(LIVE).append('/').append(id).append("; ")
                    .append("if grep -q \" $P \" /proc/mounts; then echo \"mounted   $P\"; ")
                    .append("elif [ -d \"$M\" ]; then echo \"unmounted $P (layer present)\"; ")
                    .append("else echo \"unmounted $P\"; fi; ");
        }
        RootClient.Result r = RootClient.run(cmd.toString(), 30000);

        StringBuilder out = new StringBuilder(r.out.trim());
        for (String path : paths) {
            File m = new File(mirrorDir(ctx), id(path) + ".tar");
            out.append("\nmirror    ").append(m.getName()).append(": ")
                    .append(m.isFile() ? m.length() + " bytes" : "none");
        }
        return out.toString();
    }

    /** Unmounts a layer, removes it from the list and deletes the mirror. */
    public static String remove(Context ctx, String rawPath) {
        String path = normalize(rawPath);
        if (path == null) {
            return "rw remove: enter an absolute path (not /)";
        }
        String id = id(path);
        RootClient.Result r = RootClient.run(
                "P=" + sq(path) + "; "
                        + "umount \"$P\" 2>/dev/null || umount -l \"$P\" 2>/dev/null; "
                        + "rm -rf " + LIVE + "/" + id + "; rm -f " + STAGE + "/" + id + ".tar; "
                        + "if grep -q \" $P \" /proc/mounts; then echo 'still mounted'; "
                        + "else echo 'unmounted'; fi", 60000);

        File mirror = new File(mirrorDir(ctx), id + ".tar");
        boolean gone = !mirror.exists() || mirror.delete();

        List<String> paths = paths(ctx);
        paths.remove(path);
        store(ctx, paths);

        return "rw remove " + path + "\n" + r.toString()
                + (gone ? "" : "\ncould not delete " + mirror);
    }

    /** Re-applies every configured layer from its mirror. Blocking. */
    public static String applyAll(Context ctx) {
        List<String> paths = paths(ctx);
        if (paths.isEmpty()) {
            return "(no rw layers to apply)";
        }
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            out.append(mountLayer(ctx, path)).append('\n');
        }
        return out.toString().trim();
    }

    public static boolean hasLayers(Context ctx) {
        return !paths(ctx).isEmpty();
    }

    // ------------------------------------------------------------- internals

    private static String mountLayer(Context ctx, String path) {
        String id = id(path);
        if (!prepare()) {
            return path + ": cannot create the live/staging tmpfs";
        }

        File mirror = new File(mirrorDir(ctx), id + ".tar");
        String stage = STAGE + "/" + id + ".tar";

        if (mirror.isFile()) {
            if (!push(mirror, new File(stage))) {
                return path + ": cannot stage " + mirror;
            }
        } else {
            RootClient.run("rm -f " + stage + "; echo ok", 10000);
        }

        // Page-cache warm: kernel root has no fscrypt key for cold /data inodes.
        RootClient.warm(path);

        RootClient.Result r = RootClient.run(script(path, id), 180000);
        return r.toString();
    }

    /** Creates and mounts the live tmpfs, and makes staging app-writable. */
    private static boolean prepare() {
        RootClient.Result r = RootClient.run(
                "mkdir -p " + LIVE + " " + STAGE + "; "
                        + "grep -q ' " + LIVE + " ' /proc/mounts || "
                        + "mount -t tmpfs -o rw,exec,nosuid,nodev,mode=755,size=512m tmpfs "
                        + LIVE + "; "
                        + "chmod 777 " + STAGE + "; echo ready", 30000);
        return r.ok() && r.out.contains("ready");
    }

    private static String script(String path, String id) {
        String live = LIVE + "/" + id;
        return "L=" + live + "; P=" + sq(path) + "; T=" + STAGE + "/" + id + ".tar; "
                + "mkdir -p " + LIVE + " " + STAGE + "; "
                + "grep -q ' " + LIVE + " ' /proc/mounts || "
                + "mount -t tmpfs -o rw,exec,nosuid,nodev,mode=755,size=512m tmpfs "
                + LIVE + " || { echo 'cannot mount live tmpfs'; exit 3; }; "
                + "chmod 777 " + STAGE + "; "
                + "[ -e \"$P\" ] || { echo \"skip (missing): $P\"; exit 0; }; "
                + "grep -q \" $P \" /proc/mounts && { echo \"already mounted: $P\"; exit 0; }; "
                + "rm -rf \"$L\"; mkdir -p \"$L\"; "
                + "if [ -s \"$T\" ]; then tar -xf \"$T\" -C \"$L\" "
                + "|| { echo 'tar restore failed'; exit 4; }; "
                + "elif [ -d \"$P\" ]; then cp -a \"$P\"/. \"$L\"/ "
                + "|| { echo 'layer copy failed'; exit 5; }; "
                + "else cp -a \"$P\" \"$L/payload\" "
                + "|| { echo 'layer copy failed'; exit 5; }; fi; "
                + "if [ -d \"$P\" ]; then SRC=\"$L\"; else SRC=\"$L/payload\"; fi; "
                + "mount --bind \"$SRC\" \"$P\" || { echo 'bind failed'; exit 6; }; "
                + "echo \"mounted $SRC -> $P\"";
    }

    // ---------------------------------------------------------------- files

    private static File mirrorDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "rw");
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static boolean push(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean pull(File src, File dst) {
        return push(src, dst);
    }

    // ---------------------------------------------------------------- state

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> paths(Context ctx) {
        String raw = prefs(ctx).getString(KEY_PATHS, "");
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                if (!line.trim().isEmpty()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }

    private static void store(Context ctx, List<String> paths) {
        prefs(ctx).edit().putString(KEY_PATHS, String.join("\n", paths)).apply();
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith("/") || "/".equals(p)) {
            return null;
        }
        return p;
    }

    private static String id(String path) {
        String base = path.replaceAll("^/+", "").replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base + "-" + Integer.toHexString(path.hashCode());
    }

    private static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Used by diagnostics. */
    static String describe() {
        return String.join(", ", Arrays.asList(LIVE, STAGE));
    }
}
