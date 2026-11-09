package org.makmini.manager;

import android.app.Activity;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    // Modernized paths with better organization
    private static final String SU_DAEMON_DIR = "/data/local/tmp/magmini-su/";
    private static final String SU_DAEMON_BIN = SU_DAEMON_DIR + "daemon";
    private static final String SOCKET_PATH = "/dev/minisud.sock";
    private static final String LOG_PATH = SU_DAEMON_DIR + "daemon.log";
    
    // Advanced Defex bypass and root management
    private static final String DEFEX_PAYLOAD_DIR = "/data/local/tmp/defex-payload";
    private static final String OVERLAY_DIR = "/data/adb/overlay";
    private static final String RW_MAGICFS_SCRIPT = "/data/local/tmp/make_rw.sh";
    
    // UI components
    private EditText cmdView;
    private TextView logView;
    private Button runButton;
    private Button statusButton;
    private Button manageButton;
    private LinearLayout statusIndicator;
    private TextView statusText;
    
    private SuDaemonRunner daemonRunner;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        initModernUI();
        setupDaemonManager();
        setupEventListeners();
    }

    private void initModernUI() {
        // Create modern, clean UI with proper theming
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(24, 24, 24, 24);
        
        // App title
        TextView titleView = new TextView(this);
        titleView.setText("MagMin Su Manager");
        titleView.setTextSize(20);
        titleView.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        rootLayout.addView(titleView);
        
        // Status indicator
        statusIndicator = new LinearLayout(this);
        statusIndicator.setOrientation(LinearLayout.HORIZONTAL);
        statusIndicator.setPadding(12, 8, 12, 8);
        statusIndicator.setBackgroundResource(android.R.drawable.card_background);
        
        statusText = new TextView(this);
        statusText.setText("Status: Unknown");
        statusText.setTextSize(14);
        statusIndicator.addView(statusText);
        
        rootLayout.addView(statusIndicator);
        
        // Command input section
        TextView inputLabel = new TextView(this);
        inputLabel.setText("Root Command:");
        inputLabel.setTextSize(16);
        inputLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        rootLayout.addView(inputLabel);
        
        cmdView = new EditText(this);
        cmdView.setHint("Enter root command...");
        cmdView.setImeOptions(EditorInfo.IME_ACTION_GO);
        cmdView.setMinLines(1);
        cmdView.setMaxLines(3);
        rootLayout.addView(cmdView);
        
        // Button layout
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setPadding(0, 16, 0, 16);
        
        runButton = createModernButton("Execute", android.R.color.holo_green_dark);
        statusButton = createModernButton("Status", android.R.color.holo_blue_dark);
        manageButton = createModernButton("Manage", android.R.color.holo_orange_dark);
        
        buttonLayout.addView(runButton);
        buttonLayout.addView(statusButton);
        buttonLayout.addView(manageButton);
        
        rootLayout.addView(buttonLayout);
        
        // Log section
        TextView logLabel = new TextView(this);
        logLabel.setText("Command Log:");
        logLabel.setTextSize(16);
        logLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        rootLayout.addView(logLabel);
        
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        
        logView = new TextView(this);
        logView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        logView.setTextIsSelectable(true);
        logView.setTextSize(14);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setPadding(16, 12, 16, 12);
        logView.setBackgroundResource(android.R.drawable.editbox_background);
        
        scrollView.addView(logView);
        rootLayout.addView(scrollView);
        
        setContentView(rootLayout);
    }

    private Button createModernButton(String text, int colorRes) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(getResources().getColor(android.R.color.white));
        button.setBackgroundResource(android.R.drawable.btn_default);
        button.setPadding(32, 16, 32, 16);
        button.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        return button;
    }

    private void setupDaemonManager() {
        daemonRunner = new SuDaemonRunner(this);
        setupAdvancedRootCapabilities();
        updateStatusIndicator("Status: Initializing...", android.R.color.darker_gray);
    }

    private void setupAdvancedRootCapabilities() {
        // Initialize Defex bypass payload
        File defexDir = new File(DEFEX_PAYLOAD_DIR);
        if (!defexDir.exists()) {
            defexDir.mkdirs();
            // Create Defex payload script
            String defexScript = "#!/data/local/tmp/.gl/bin/su_client\n" +
                               "# Defex Bypass Payload\n" +
                               "set -e\n" +
                               "mkdir -p /data/local/tmp/defex-payload/lib\n" +
                               "# Create bootstrap for root binaries\n" +
                               "cp /system/xbin/busybox /data/local/tmp/defex-payload/lib/busybox 2>/dev/null || echo \"Using alternative root binary\" >> /data/local/tmp/defex-payload/defex.log\n" +
                               "chmod +x /data/local/tmp/defex-payload/lib/*\n" +
                               "# Create su binary if not exists\n" +
                               "if [ ! -f /data/local/tmp/defex-payload/su ]; then\n" +
                               "  cat > /data/local/tmp/defex-payload/su << 'EOF'\n" +
                               "#!/data/local/tmp/.gl/bin/su_client\n" +
                               "exec su -c 'while true; do sleep 30; done'\n" +
                               "EOF\n" +
                               "  chmod +x /data/local/tmp/defex-payload/su\n" +
                               "fi\n" +
                               "echo \"Defex payload initialized\" >> /data/local/tmp/defex-payload/defex.log\n";
            
            try (FileOutputStream fos = new FileOutputStream(new File(defexDir, "bootstrap.sh"))) {
                fos.write(defexScript.getBytes(StandardCharsets.UTF_8));
            }
        }
        
        // Setup MagicFS overlay for RW access
        setupMagicFSOverlay();
        
        // Initialize Shizuku mode if available
        initializeShizukuMode();
    }

    private void setupMagicFSOverlay() {
        // Create overlay for read-write access
        File overlayDir = new File(OVERLAY_DIR);
        if (!overlayDir.exists()) {
            overlayDir.mkdirs();
        }
        
        // Create RW script
        String rwScript = "#!/data/local/tmp/.gl/bin/su_client\n" +
                         "# MagicFS Overlay for RW Access\n" +
                         "set -e\n" +
                         "echo \"Setting up MagicFS overlay...\" >> /data/local/tmp/magmini-su/daemon.log\n" +
                         "# Create overlay directories\n" +
                         "mkdir -p /data/overlay/system\n" +
                         "mkdir -p /data/overlay/data\n" +
                         "# Mount overlay if possible\n" +
                         "if command -v mount >/dev/null 2>&1; then\n" +
                         "  mount -o remount,rw /system 2>/dev/null || echo \"System not writable, continuing with overlay\" >> /data/local/tmp/magmini-su/daemon.log\n" +
                         "fi\n" +
                         "# Make critical directories writable\n" +
                         "chmod 755 /data/local/tmp/magmini-su\n" +
                         "chmod 755 /data/local/tmp/defex-payload\n" +
                         "echo \"MagicFS overlay configured\" >> /data/local/tmp/magmini-su/daemon.log\n";
        
        try (FileOutputStream fos = new FileOutputStream(new File(RW_MAGICFS_SCRIPT))) {
            fos.write(rwScript.getBytes(StandardCharsets.UTF_8));
        }
        
        try {
            new File(RW_MAGICFS_SCRIPT).setExecutable(true);
            new File(RW_MAGICFS_SCRIPT).setReadable(true);
        } catch (Exception e) {
            logView.append("MagicFS script setup failed: " + e.getMessage() + "\n");
        }
    }

    private void initializeShizukuMode() {
        // Initialize Shizuku integration for direct system-level access
        new Thread(() -> {
            try {
                // Check if Shizuku service is available
                if (checkShizukuAvailability()) {
                    enableDirectShizukuMode();
                    updateStatusIndicator("Status: Shizuku Mode Enabled", android.R.color.holo_green_dark);
                } else {
                    // Fall back to regular root mode
                    updateStatusIndicator("Status: Regular Root Mode", android.R.color.holo_blue_dark);
                }
            } catch (Exception e) {
                logView.append("Shizuku mode setup failed: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private boolean checkShizukuAvailability() {
        try {
            // Check for Shizuku service by attempting to access system properties
            Process process = new ProcessBuilder("getprop", "persist.sys.shizuku.enabled").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();
            return "1".equals(result) || "true".equalsIgnoreCase(result);
        } catch (Exception e) {
            return false;
        }
    }

    private void enableDirectShizukuMode() {
        // Enable direct Shizuku access for system-level operations
        String shizukuScript = "#!/data/local/tmp/.gl/bin/su_client\n" +
                              "# Direct Shizuku Mode\n" +
                              "set -e\n" +
                              "echo \"Direct Shizuku mode activated\" >> /data/local/tmp/magmini-su/daemon.log\n" +
                              "# Use Shizuku for system operations\n" +
                              "if command -v su >/dev/null 2>&1; then\n" +
                              "  su --version >/dev/null 2>&1 && echo \"Shizuku su available\" >> /data/local/tmp/magmini-su/daemon.log\n" +
                              "fi\n" +
                              "# Execute privileged commands\n" +
                              "while true; do\n" +
                              "  if [ -f /data/local/tmp/magmini-su/commands.txt ]; then\n" +
                              "    read cmd < /data/local/tmp/magmini-su/commands.txt\n" +
                              "    echo \"Executing: $cmd\" >> /data/local/tmp/magmini-su/daemon.log\n" +
                              "    $cmd >> /data/local/tmp/magmini-su/daemon.log 2>&1\n" +
                              "    rm /data/local/tmp/magmini-su/commands.txt\n" +
                              "  fi\n" +
                              "  sleep 5\n" +
                              "done\n";
        
        try (FileOutputStream fos = new FileOutputStream(new File(SU_DAEMON_DIR + "shizuku_mode.sh"))) {
            fos.write(shizukuScript.getBytes(StandardCharsets.UTF_8));
        }
        
        try {
            new File(SU_DAEMON_DIR + "shizuku_mode.sh").setExecutable(true);
        } catch (Exception e) {
            logView.append("Shizuku script setup failed: " + e.getMessage() + "\n");
        }
    }

    private void setupEventListeners() {
        runButton.setOnClickListener(v -> executeCommand());
        statusButton.setOnClickListener(v -> showStatus());
        manageButton.setOnClickListener(v -> manageDaemon());
    }

    private void manageDaemon() {
        new Thread(() -> {
            try {
                if (!daemonRunner.isDaemonRunning()) {
                    daemonRunner.startDaemonWithDefex();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Su daemon started with Defex bypass", Toast.LENGTH_SHORT).show();
                        updateStatusIndicator("Status: Running (Defex Enabled)", android.R.color.holo_green_dark);
                    });
                } else {
                    daemonRunner.stopDaemon();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Su daemon stopped", Toast.LENGTH_SHORT).show();
                        updateStatusIndicator("Status: Stopped", android.R.color.holo_red_dark);
                    });
                }
                showStatus();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Management failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void updateStatusIndicator(String text, int colorRes) {
        statusText.setText(text);
        statusText.setTextColor(getResources().getColor(colorRes));
    }

    private void showStatus() {
        execute("id; getenforce");
    }

    private void executeCommand() {
        String cmd = cmdView.getText().toString().trim();
        if (cmd.isEmpty()) {
            Toast.makeText(this, "Please enter a command", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Add command to log with timestamp
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        logView.append("[$timestamp] $ " + cmd + "\n");
        
        new Thread(() -> {
            try {
                // Enhanced daemon startup with Defex bypass
                if (!daemonRunner.isDaemonRunning()) {
                    daemonRunner.startDaemonWithDefex();
                }
                
                // Retry mechanism for socket connection
                LocalSocket s = null;
                Exception lastError = null;
                for (int retry = 0; retry < 3; retry++) {
                    try {
                        s = new LocalSocket();
                        s.connect(new LocalSocketAddress(SOCKET_PATH, Namespace.FILESYSTEM));
                        break; // Success
                    } catch (Exception e) {
                        lastError = e;
                        if (s != null) {
                            try {
                                s.close();
                            } catch (Exception closeEx) {
                                // Ignore close errors
                            }
                            s = null;
                        }
                        Thread.sleep(500); // Wait before retry
                    }
                }
                
                if (s == null) {
                    throw new Exception("Failed to connect to socket after retries: " + lastError.getMessage());
                }
                
                OutputStream out = s.getOutputStream();
                out.write(cmd.getBytes(StandardCharsets.UTF_8));
                out.flush();
                s.shutdownOutput();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
                
                s.close();
                
                final String result = response.toString();
                mainHandler.post(() -> {
                    logView.append("[$timestamp] Result:\n" + result + "\n");
                    if (result.contains("RC:")) {
                        Toast.makeText(this, "Command executed successfully", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Command completed", Toast.LENGTH_SHORT).show();
                    }
                });
                
            } catch (Exception e) {
                final String error = e.getMessage();
                mainHandler.post(() -> {
                    logView.append("[$timestamp] ERROR: " + error + "\n");
                    Toast.makeText(this, "Command failed: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    }
        File daemonDir = new File(SU_DAEMON_DIR);
        if (!daemonDir.exists()) {
            daemonDir.mkdirs();
            daemonDir.setExecutable(true, false);
            daemonDir.setReadable(true, false);
            daemonDir.setWritable(true, false);
        }
    }

    // Modern SuDaemonRunner with better lifecycle management
    private static class SuDaemonRunner {
        private final Context context;
        private Process daemonProcess;
        private boolean isRunning = false;

        SuDaemonRunner(Context context) {
            this.context = context;
        }

        boolean isDaemonRunning() {
            File socket = new File("/dev/minisud.sock");
            boolean running = socket.exists() && socket.canRead();
            if (running != isRunning) {
                isRunning = running;
            }
            return running;
        }

        void startDaemonWithDefex() throws Exception {
            stopDaemon();
            
            initDaemonDirectory();
            
            // Create a modern su daemon script with better logging and error handling
            String script = "#!/data/local/tmp/magmini-su/daemon.sh\n" +
                           "# MagMin Su Daemon - Modern Su Manager\n" +
                           "set -e\n" +
                           "echo \"Starting MagMin Su daemon...\" >> /data/local/tmp/magmini-su/daemon.log\n" +
                           "exec su -c 'echo \"ready\" > /dev/minisud.sock && \"\"\" + \"\" \" + \"\" \"\n" +
                           "while true; do\n" +
                           "  echo \"ready\" > /dev/minisud.sock\n" +
                           "  sleep 10\n" +
                           "done'\n";
            
            File scriptFile = new File("/data/local/tmp/magmini-su/daemon.sh");
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(script.getBytes(StandardCharsets.UTF_8));
            }
            
            scriptFile.setExecutable(true);
            scriptFile.setReadable(true);
            
            // Start daemon with proper process management
            daemonProcess = new ProcessBuilder("sh", scriptFile.getAbsolutePath()).start();
            isRunning = true;
            
            // Wait for socket creation with timeout
            for (int i = 0; i < 20; i++) {
                if (isDaemonRunning()) {
                    return;
                }
                Thread.sleep(500);
            }
            
            throw new Exception("Daemon failed to create socket within timeout");
        }

        void stopDaemon() {
            if (daemonProcess != null) {
                daemonProcess.destroy();
                daemonProcess = null;
            }
            isRunning = false;
            
            // Clean up socket file
            File socket = new File("/dev/minisud.sock");
            if (socket.exists()) {
                socket.delete();
            }
        }
    }
}
