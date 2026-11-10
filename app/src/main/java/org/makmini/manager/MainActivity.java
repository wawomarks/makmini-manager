package org.makmini.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * makmini manager -- root/su manager, Termux-Boot replacement, start log
 * viewer, policy editor, RW layer tools and Shizuku helper.
 */
public class MainActivity extends Activity {

    private static final String GL = "/data/local/tmp/.gl";
    private static final String GLM = "sh " + GL + "/manager/glm";
    private static final String POLICY = "/dev/minisud.policy";
    private static final String ARM_CMD = "sh /data/local/tmp/glroot.sh true";

    private static final String STATUS_CMD =
            "echo uid=$(id -u); "
                    + "echo model=$(getprop ro.product.model); "
                    + "echo release=$(getprop ro.build.version.release); "
                    + "echo selinux=$(getenforce); "
                    + "echo helper=$(cat /proc/sys/kernel/modprobe 2>/dev/null); "
                    + "echo pid=$(cat /dev/minisud.pid 2>/dev/null); "
                    + "echo mode=$(head -n 1 " + POLICY + " 2>/dev/null); "
                    + "echo shim=$(ls -l /odm/bin/su 2>/dev/null | tr -s ' ')";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder console = new StringBuilder();

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private EditText cmdView;
    private EditText policyView;
    private EditText rwView;
    private Button modeButton;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        cmdView = findViewById(R.id.cmd);
        policyView = findViewById(R.id.policyEntry);
        rwView = findViewById(R.id.rwPath);
        modeButton = findViewById(R.id.btnMode);

        cmdView.setText("id");

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshStatus());
        findViewById(R.id.btnArm).setOnClickListener(v -> showArmHelp());
        findViewById(R.id.btnDeploy).setOnClickListener(v -> deployKit());
        findViewById(R.id.btnInstallSu).setOnClickListener(v -> installSu());
        findViewById(R.id.btnStartDaemon).setOnClickListener(v -> startDaemon());
        findViewById(R.id.btnStopDaemon).setOnClickListener(v ->
                runCmd("stop minisud", GL + "/bin/minisud --stop", 20000));

        findViewById(R.id.btnRun).setOnClickListener(v -> runInputCommand());
        findViewById(R.id.btnWhoami).setOnClickListener(v -> runCmd("id", "id", 20000));
        findViewById(R.id.btnClear).setOnClickListener(v -> clearConsole());

        findViewById(R.id.btnMode).setOnClickListener(v -> toggleMode());
        findViewById(R.id.btnAllowSelf).setOnClickListener(v -> allowUid(android.os.Process.myUid()));
        findViewById(R.id.btnAllow).setOnClickListener(v -> changePolicy(true));
        findViewById(R.id.btnDeny).setOnClickListener(v -> changePolicy(false));
        findViewById(R.id.btnPolicyList).setOnClickListener(v -> showPolicy());

        findViewById(R.id.btnRwAdd).setOnClickListener(v -> rwAction("add"));
        findViewById(R.id.btnRwList).setOnClickListener(v -> rwAction("list"));
        findViewById(R.id.btnRwSave).setOnClickListener(v -> rwAction("save"));

        findViewById(R.id.btnBootDir).setOnClickListener(v -> prepareBootDir());
        findViewById(R.id.btnBootRun).setOnClickListener(v ->
                runCmd("run boot scripts", "sh " + GL + "/bin/makmini_boot.sh", 180000));
        findViewById(R.id.btnBootLog).setOnClickListener(v -> showStartLog());

        findViewById(R.id.btnShizuku).setOnClickListener(v -> shizukuStatus());
        findViewById(R.id.btnShizukuApp).setOnClickListener(v -> openShizuku());

        appendLog("makmini manager started (uid " + android.os.Process.myUid() + ")");
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null && !refreshing) {
            refreshStatus();
        }
    }

    // ---------------------------------------------------------------- status

    private void refreshStatus() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        statusView.setText("checking root transport…");

        new Thread(() -> {
            boolean alive = RootClient.daemonAlive();
            String su = RootClient.suBinary();
            RootClient.Result r = RootClient.run(STATUS_CMD, 25000);
            String text = buildStatus(alive, su, r);
            ui.post(() -> {
                statusView.setText(text);
                updateModeButton(r.out);
                refreshing = false;
            });
        }, "status").start();
    }

    private String buildStatus(boolean daemonAlive, String su, RootClient.Result r) {
        StringBuilder sb = new StringBuilder();
        String rootUid = field(r.out, "uid=");
        sb.append("app uid   : ").append(android.os.Process.myUid());
        if (!rootUid.isEmpty()) {
            sb.append("   (root shell uid ").append(rootUid).append(')');
        }
        sb.append('\n');

        sb.append("device    : ").append(android.os.Build.MODEL)
                .append(" / Android ").append(android.os.Build.VERSION.RELEASE).append('\n');

        String selinux = field(r.out, "selinux=");
        if (!selinux.isEmpty()) {
            sb.append("selinux   : ").append(selinux).append('\n');
        }

        String helper = field(r.out, "helper=");
        sb.append("root      : ")
                .append(helper.isEmpty() ? "not armed" : "armed (" + helper + ")")
                .append('\n');

        String pid = field(r.out, "pid=");
        sb.append("minisud   : ")
                .append(daemonAlive ? "running" + (pid.isEmpty() ? "" : " (pid " + pid + ")") : "not running")
                .append('\n');

        String mode = field(r.out, "mode=");
        sb.append("policy    : ")
                .append(mode.contains("enforce") ? "enforce" : "permissive (every uid)")
                .append('\n');

        sb.append("su shim   : ").append(su == null ? "missing" : su).append('\n');

        if (r.rc == 127 || (su == null && !daemonAlive)) {
            sb.append("\nNo root transport yet. Arm root, then deploy the kit:\n")
                    .append("  adb shell ").append(ARM_CMD).append('\n');
        } else if (!r.ok()) {
            sb.append("\nlast transport error: ").append(r.out.trim()).append('\n');
        }
        return sb.toString();
    }

    private void updateModeButton(String policyText) {
        boolean enforce = policyText != null && policyText.contains("mode enforce");
        modeButton.setText(enforce ? "Mode: enforce" : "Mode: permissive");
    }

    private static String field(String text, String key) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            if (line.startsWith(key)) {
                return line.substring(key.length()).trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------- commands

    private void runCmd(String label, String command, int timeoutMs) {
        appendLog("$ " + label);
        new Thread(() -> {
            RootClient.Result r = RootClient.run(command, timeoutMs);
            ui.post(() -> {
                appendLog(r.toString());
                updateModeButton(r.out);
            });
        }, "cmd").start();
    }

    private void runInputCommand() {
        String command = cmdView.getText().toString().trim();
        if (command.isEmpty()) {
            toast("enter a command");
            return;
        }
        runCmd(command, command, 120000);
    }

    private void installSu() {
        runCmd("install su shim", "D=" + GL + "; "
                + "mkdir -p /odm/bin; "
                + "cp -f $D/bin/su_client /odm/bin/su; "
                + "chmod 755 /odm/bin/su; "
                + "[ -f " + POLICY + " ] || printf 'mode permissive\\n' > " + POLICY + "; "
                + "if [ -e /system/bin/su ]; then mount --bind /odm/bin/su /system/bin/su 2>/dev/null "
                + "&& echo 'bound over /system/bin/su'; fi; "
                + "ls -l /odm/bin/su /system/bin/su 2>&1; "
                + GLM + " mode permissive 2>&1 | tail -n 3", 90000);
    }

    private void deployKit() {
        appendLog("$ deploy kit from APK assets");
        new Thread(() -> {
            String out = Kit.deploy(this);
            ui.post(() -> {
                appendLog(out);
                refreshStatus();
            });
        }, "deploy").start();
    }

    private void startDaemon() {
        new Thread(() -> {
            if (RootClient.daemonAlive()) {
                ui.post(() -> appendLog("minisud is already running"));
                return;
            }
            ui.post(() -> appendLog("$ start minisud"));
            RootClient.Result r = RootClient.run(
                    "nohup " + GL + "/bin/minisud >/dev/null 2>&1 & "
                            + "i=0; while [ $i -lt 20 ]; do [ -S " + RootClient.SOCK
                            + " ] && break; sleep 0.5; i=$((i+1)); done; "
                            + "cat /dev/minisud.pid 2>/dev/null", 30000);
            boolean alive = RootClient.daemonAlive();
            ui.post(() -> {
                String out = r.out.trim();
                if (!out.isEmpty()) {
                    appendLog(out);
                }
                appendLog(alive ? "minisud is up" : "minisud did not start (root not armed?)");
                refreshStatus();
            });
        }, "start-daemon").start();
    }

    // --------------------------------------------------------------- policy

    private void toggleMode() {
        new Thread(() -> {
            RootClient.Result cur = RootClient.run("cat " + POLICY + " 2>/dev/null", 15000);
            boolean permissive = !cur.out.contains("mode enforce");
            String next = permissive ? "enforce" : "permissive";
            RootClient.Result r = RootClient.run(
                    "P=" + POLICY + "; { echo 'mode " + next + "'; grep '^allow ' $P 2>/dev/null; } "
                            + "> $P.tmp; mv $P.tmp $P; cat $P", 20000);
            ui.post(() -> {
                appendLog("policy mode -> " + next);
                appendLog(r.toString());
                updateModeButton("mode " + next);
                if ("enforce".equals(next)) {
                    appendLog("enforce: only root, system (1000), shell (2000) and listed uids may use su");
                }
            });
        }, "mode").start();
    }

    private void changePolicy(boolean allow) {
        String spec = policyView.getText().toString().trim();
        if (spec.isEmpty()) {
            toast("enter a package name or uid");
            return;
        }
        new Thread(() -> {
            String uid = resolveUid(spec);
            if (uid == null) {
                ui.post(() -> appendLog("unknown package/uid: " + spec));
                return;
            }
            RootClient.Result r = RootClient.run(allow ? allowScript(uid) : denyScript(uid), 20000);
            ui.post(() -> {
                appendLog((allow ? "allow " : "deny ") + spec + " (uid " + uid + ")");
                appendLog(r.toString());
                updateModeButton(r.out);
            });
        }, "policy").start();
    }

    private void allowUid(int uid) {
        new Thread(() -> {
            RootClient.Result r = RootClient.run(allowScript(Integer.toString(uid)), 20000);
            ui.post(() -> {
                appendLog("allow this app (uid " + uid + ")");
                appendLog(r.toString());
                updateModeButton(r.out);
            });
        }, "allow-self").start();
    }

    private static String allowScript(String uid) {
        return "P=" + POLICY + "; [ -f $P ] || printf 'mode permissive\\n' > $P; "
                + "grep -qx 'allow " + uid + "' $P || printf 'allow " + uid + "\\n' >> $P; cat $P";
    }

    private static String denyScript(String uid) {
        return "P=" + POLICY + "; [ -f $P ] || printf 'mode permissive\\n' > $P; "
                + "grep -vx 'allow " + uid + "' $P > $P.tmp; mv $P.tmp $P; cat $P";
    }

    private String resolveUid(String spec) {
        if (spec.matches("[0-9]+")) {
            return spec;
        }
        RootClient.Result r = RootClient.run("pm list packages -U", 60000);
        for (String line : r.out.split("\n")) {
            if (!line.startsWith("package:")) {
                continue;
            }
            int at = line.indexOf(" uid:");
            if (at < 0) {
                continue;
            }
            String pkg = line.substring("package:".length(), at).trim();
            if (pkg.equals(spec)) {
                return line.substring(at + " uid:".length()).trim();
            }
        }
        return null;
    }

    private void showPolicy() {
        runCmd("policy + access log", "echo '--- policy ---'; "
                + "cat " + POLICY + " 2>/dev/null || echo '(none: permissive)'; echo; "
                + "echo '--- last su requests ---'; "
                + "tail -n 30 /dev/minisud.log 2>/dev/null || echo '(no requests yet)'", 25000);
    }

    // ------------------------------------------------------------------- rw

    private void rwAction(String action) {
        if ("add".equals(action)) {
            String path = rwView.getText().toString().trim();
            if (!path.startsWith("/")) {
                toast("enter an absolute path");
                return;
            }
            runCmd("glm rw add " + path, GLM + " rw add '" + escape(path) + "'", 240000);
            return;
        }
        if ("save".equals(action)) {
            runCmd("glm rw save", GLM + " rw save", 240000);
            return;
        }
        runCmd("glm rw list", GLM + " rw list", 30000);
    }

    private static String escape(String s) {
        return s.replace("'", "'\\''");
    }

    // ----------------------------------------------------------------- boot

    private void prepareBootDir() {
        File base = getExternalFilesDir(null);
        if (base == null) {
            toast("external storage is not available");
            return;
        }
        File dir = new File(base, "boot");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            toast("cannot create " + dir);
            return;
        }

        File sample = new File(dir, "00-example.sh");
        if (!sample.exists()) {
            try (FileOutputStream out = new FileOutputStream(sample)) {
                out.write(("#!/system/bin/sh\n"
                        + "echo \"makmini boot $(date)\"\n").getBytes(StandardCharsets.UTF_8));
                //noinspection ResultOfMethodCallIgnored
                sample.setExecutable(true, false);
            } catch (IOException e) {
                toast("cannot write sample: " + e.getMessage());
            }
        }

        appendLog("boot scripts directory:\n" + dir.getAbsolutePath()
                + "\n\nPut *.sh files here (and/or in /sdcard/makmini/boot)."
                + "\nThey run as root after boot via " + GL + "/bin/makmini_boot.sh.");
        copyToClipboard(dir.getAbsolutePath());
        toast("boot dir ready (path copied)");
    }

    private void showStartLog() {
        appendLog("--- app start log ---\n" + BootLog.read(this));
        runCmd("device boot log", "tail -n 40 /dev/makmini_boot.log 2>/dev/null "
                + "|| echo '(no device boot log yet)'", 20000);
    }

    // --------------------------------------------------------------- shizuku

    private void shizukuStatus() {
        String pkg = "moe.shizuku.privileged.api";
        boolean installed;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        appendLog("shizuku app: " + (installed ? "installed" : "not installed"));
        runCmd("shizuku process + su socket",
                "ps -A -o NAME 2>/dev/null | grep -i shizuku | sort -u | head -n 8; "
                        + "echo; echo 'minisud socket:'; ls -l " + RootClient.SOCK + " 2>/dev/null; "
                        + "echo; echo 'Shizuku runs as uid 2000 and can exec /odm/bin/su directly.'",
                20000);
    }

    private void openShizuku() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (intent == null) {
            toast("Shizuku is not installed");
            return;
        }
        startActivity(intent);
    }

    // ---------------------------------------------------------------- helper

    private void showArmHelp() {
        String message = "Root is temporary on this device and is not armable from inside an app.\n\n"
                + "From a computer:\n"
                + "  adb shell " + ARM_CMD + "\n\n"
                + "From Termux (rooted shell or adb):\n"
                + "  " + ARM_CMD + "\n\n"
                + "Then press:\n"
                + "  1. Deploy kit\n"
                + "  2. Install su\n"
                + "  3. Start daemon\n\n"
                + "Never run 'glroot.sh --boot' on this model: it hangs the device "
                + "after 'ssopt pre paint'.";

        new AlertDialog.Builder(this)
                .setTitle("Arm root")
                .setMessage(message)
                .setPositiveButton("Copy command", (d, w) -> {
                    copyToClipboard(ARM_CMD);
                    toast("command copied");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void clearConsole() {
        console.setLength(0);
        logView.setText("");
    }

    private void appendLog(String text) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        console.append('[').append(stamp).append("] ").append(text == null ? "" : text).append('\n');
        if (console.length() > 120000) {
            console.delete(0, console.length() - 100000);
        }
        logView.setText(console.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("makmini", text));
        }
    }
}
