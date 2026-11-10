package org.makmini.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Termux-Boot style start hook.
 *
 * <p>After boot (or an app update) it tries the daemon, then the GhostLock
 * channel, then a su binary. When root is available it starts minisud through
 * the DEFEX launcher, runs the user's boot scripts and re-applies saved RW
 * layers. Everything is recorded in the start log.
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
                    BootLog.append(context, "minisud not running, trying the defex launcher");
                    RootClient.warmKit();
                    RootClient.Result started = RootClient.run(
                            RootClient.startDaemonCommand(), 40000);
                    BootLog.append(context, "daemon: " + oneline(started));
                }

                RootClient.warm(GL + "/bin/makmini_boot.sh");
                RootClient.Result r = RootClient.run(
                        "sh " + GL + "/bin/makmini_boot.sh", 180000);
                BootLog.append(context, "boot scripts: " + r.out.trim());

                if (!r.ok()) {
                    BootLog.append(context, "boot scripts rc=" + r.rc + " via " + r.via
                            + (RootClient.suBinary() == null
                            ? " (no su binary: root is not armed yet)" : ""));
                }

                if (RwLayer.hasLayers(context)) {
                    BootLog.append(context, "rw layers: " + oneline(RwLayer.applyAll(context)));
                }
            } catch (Throwable t) {
                BootLog.append(context, "boot handler error: " + t);
            } finally {
                pending.finish();
            }
        }, "makmini-boot").start();
    }

    private static String oneline(RootClient.Result r) {
        String out = r.out.trim().replace('\n', ' ');
        return (out.isEmpty() ? "" : out + " ") + "[rc=" + r.rc + " via " + r.via + "]";
    }

    private static String oneline(String text) {
        return text == null ? "" : text.trim().replace('\n', ' ');
    }
}
