package org.makmini.manager;

import android.app.Activity;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.Bundle;
import android.os.StrictMode;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    // Paths for Magisk/KsuNext-like su daemon and manager
    private static final String SU_DAEMON_PATH = "/data/local/tmp/magmini-su_daemon";
    private static final String SU_MANAGER_PATH = "/data/local/tmp/magmini-su_manager";
    private static final String SOCKET_PATH = "/dev/minisud.sock";
    
    private EditText cmdView;
    private TextView logView;
    private SuDaemonRunner daemonRunner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cmdView = findViewById(R.id.cmd);
        logView = findViewById(R.id.log);
        Button run = findViewById(R.id.run);
        Button status = findViewById(R.id.status);
        Button manage = findViewById(R.id.manage);

        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());

        // Initialize the Su manager
        daemonRunner = new SuDaemonRunner(this);
        
        run.setOnClickListener(v -> execute(cmdView.getText().toString()));
        status.setOnClickListener(v -> execute("id; getenforce"));
        manage.setOnClickListener(v -> manageSuDaemon());
    }

    private void manageSuDaemon() {
        new Thread(() -> {
            try {
                // Check if daemon exists and is running
                if (!daemonRunner.isDaemonRunning()) {
                    // Start the daemon
                    daemonRunner.startDaemon();
                    runOnUiThread(() -> Toast.makeText(this, "Su daemon started", Toast.LENGTH_SHORT).show());
                } else {
                    // Stop the daemon
                    daemonRunner.stopDaemon();
                    runOnUiThread(() -> Toast.makeText(this, "Su daemon stopped", Toast.LENGTH_SHORT).show());
                }
                // Refresh status
                execute("id; getenforce");
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Management failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void execute(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            // Ensure su daemon is running before command execution
            if (!daemonRunner.isDaemonRunning()) {
                daemonRunner.startDaemon();
            }
            
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCKET_PATH, Namespace.FILESYSTEM));
            OutputStream out = s.getOutputStream();
            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            s.shutdownOutput();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            s.close();
            sb.append("RC:").append(baos.toString()).append("\n");
            Toast.makeText(this, "Command finished", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            sb.append("ERR: ").append(e.getMessage()).append("\n");
            Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show();
        }
        logView.append(sb.toString());
    }

    // SuDaemonRunner inner class to handle daemon lifecycle
    private static class SuDaemonRunner {
        private final Context context;
        private Process daemonProcess;

        SuDaemonRunner(Context context) {
            this.context = context;
        }

        boolean isDaemonRunning() {
            // Check if the socket exists and is accessible
            File socket = new File("/dev/minisud.sock");
            return socket.exists() && socket.canRead();
        }

        void startDaemon() throws Exception {
            // Stop any existing daemon
            stopDaemon();
            
            // Copy the pre-built daemon binary (in real scenario, you'd build from source)
            String daemonSource = "/data/local/tmp/magmini-su_daemon";
            String daemonDest = "/data/local/tmp/magmini-su_daemon";
            
            // In a real implementation, you would:
            // 1. Copy the daemon binary from assets or built output
            // 2. Set executable permissions
            // 3. Start the daemon as a background process
            // 4. Wait for socket to be created
            
            // For now, we'll simulate with a simple script
            String script = "#!/data/local/tmp/.gl/bin/su_client\n" +
                           "# Magmin Su Daemon\n" +
                           "exec su -c 'while true; do echo \"ready\" > /dev/minisud.sock; sleep 30; done'\n";
            
            File scriptFile = new File(daemonDest + ".sh");
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(script.getBytes(StandardCharsets.UTF_8));
            }
            
            // Make script executable
            scriptFile.setExecutable(true);
            
            // Start daemon
            daemonProcess = new ProcessBuilder("sh", scriptFile.getAbsolutePath()).start();
            
            // Wait a moment for socket creation
            Thread.sleep(1000);
        }

        void stopDaemon() {
            if (daemonProcess != null) {
                daemonProcess.destroy();
                daemonProcess = null;
            }
            // Clean up socket file
            File socket = new File("/dev/minisud.sock");
            if (socket.exists()) {
                socket.delete();
            }
        }
    }
}
