package org.makmini.manager;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny persistent start/boot log kept in the app's private storage. */
public final class BootLog {

    private static final String NAME = "boot.log";
    private static final long MAX_BYTES = 128 * 1024;

    private BootLog() {
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), NAME);
    }

    public static synchronized void append(Context ctx, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String text = stamp + "  " + message.trim() + "\n";

        File f = file(ctx);
        try {
            if (f.length() > MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // Boot logging must never break boot handling.
        }
    }

    public static synchronized String read(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) {
            return "(no boot log yet)";
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 32 * 1024);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            return start > 0 ? "… (tail)\n" + text : text;
        } catch (IOException e) {
            return "(cannot read boot log: " + e.getMessage() + ")";
        }
    }
}
