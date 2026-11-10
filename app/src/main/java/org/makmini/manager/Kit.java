package org.makmini.manager;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Deploys the bundled root kit (GhostLock scripts, minisud, su client, glm)
 * from APK assets into {@code /data/local/tmp} through the root transport.
 */
public final class Kit {

    /**
     * asset name, target path, mode.
     *
     * <p>Everything under /data/local/tmp/.gl matches the layout the GhostLock
     * scripts and the daemon expect.
     */
    private static final String[][] MAP = {
            {"glroot.sh", "/data/local/tmp/glroot.sh", "755"},
            {"00-root.sh", "/data/local/tmp/.gl/00-root.sh", "644"},
            {"defex", "/data/local/tmp/defex/defex", "755"},
            {"defex.sh", "/data/local/tmp/defex/defex.sh", "755"},
            {"payload.sh", "/data/local/tmp/defex/payload.sh", "755"},
            {"minisud", "/data/local/tmp/.gl/bin/minisud", "755"},
            {"su_client", "/data/local/tmp/.gl/bin/su_client", "755"},
            {"glroot_all.sh", "/data/local/tmp/.gl/bin/glroot_all.sh", "755"},
            {"init_hook_boot.sh", "/data/local/tmp/.gl/bin/init_hook_boot.sh", "755"},
            {"makmini_boot.sh", "/data/local/tmp/.gl/bin/makmini_boot.sh", "755"},
            {"deploy_su.sh", "/data/local/tmp/.gl/bin/deploy_su.sh", "755"},
            {"dfinit", "/data/local/tmp/.gl/bin/dfinit", "755"},
            {"glm", "/data/local/tmp/.gl/manager/glm", "755"},
            {"libsu.so", "/data/local/tmp/.gl/lib/libsu.so", "644"},
            {"gl_fix.sh", "/data/local/tmp/.gl/gl_fix.sh", "755"},
            {"sud_policy", "/data/local/tmp/.gl/sud_policy", "644"},
            {"busybox", "/data/local/tmp/.gl/busybox", "755"},
    };

    private Kit() {
    }

    /** Copies every asset into the app-private kit dir and returns that dir. */
    public static File extract(Context ctx) throws IOException {
        File dir = new File(ctx.getFilesDir(), "kit");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }

        byte[] buf = new byte[64 * 1024];
        for (String[] entry : MAP) {
            String asset = entry[0];
            File dst = new File(dir, asset);
            try (InputStream in = ctx.getAssets().open("bin/" + asset);
                 FileOutputStream out = new FileOutputStream(dst)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
        return dir;
    }

    /** Deploys the kit to /data/local/tmp and returns the command output. */
    public static String deploy(Context ctx) {
        File src;
        try {
            src = extract(ctx);
        } catch (IOException e) {
            return "deploy: cannot unpack assets: " + e.getMessage();
        }

        StringBuilder sh = new StringBuilder();
        sh.append("D=/data/local/tmp/.gl\n");
        sh.append("mkdir -p $D/bin $D/lib $D/manager $D/doc /data/local/tmp/defex\n");
        for (String[] entry : MAP) {
            String asset = entry[0];
            String target = entry[1];
            String mode = entry[2];
            sh.append("cp -f '").append(src.getAbsolutePath()).append('/').append(asset)
                    .append("' '").append(target).append("' && chmod ").append(mode)
                    .append(" '").append(target).append("'\n");
        }
        sh.append("echo \"kit deployed: $(ls -1 $D/bin | wc -l) files in $D/bin\"\n");

        return RootClient.run(sh.toString(), 120000).toString();
    }

    /** Renders the asset manifest, used by the UI help text. */
    public static String manifest() {
        StringBuilder sb = new StringBuilder();
        for (String[] entry : MAP) {
            sb.append(entry[1]).append('\n');
        }
        return sb.toString();
    }
}
