package org.makmini.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Termux-Boot style start hook.
 *
 * <p>After boot (or an app update) it tries the daemon, then falls back to a su
 * binary, starts minisud when needed, and runs the user's boot scripts through
 * {@code .gl/bin/makmini_boot.sh}. Everything is recorded in the start log.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String GL = "/data/local/tmp/.gl";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final String action = intent != null && intent.getAction() != null
                ? intent.getAction() : "?";

        new Thread(() -> {
            try {
                BootLog.append(context, "boot broadcast: " + action);

                if (!RootClient.daemonAlive()) {
                    BootLog.append(context, "minisud not running, trying to start it");
                    RootClient.Result started = RootClient.run(
                            "nohup " + GL + "/bin/minisud >/dev/null 2>&1 &", 15000);
                    if (!started.ok()) {
                        BootLog.append(context, "start failed: " + started.out.trim());
                    }
                }

                RootClient.Result r = RootClient.run(
                        "sh " + GL + "/bin/makmini_boot.sh", 180000);
                BootLog.append(context, "boot scripts: " + r.out.trim());

                if (!r.ok()) {
                    BootLog.append(context, "boot scripts rc=" + r.rc
                            + (RootClient.suBinary() == null
                            ? " (no su binary: root is not armed yet)" : ""));
                }
            } catch (Throwable t) {
                BootLog.append(context, "boot handler error: " + t);
            } finally {
                pending.finish();
            }
        }, "makmini-boot").start();
    }
}
