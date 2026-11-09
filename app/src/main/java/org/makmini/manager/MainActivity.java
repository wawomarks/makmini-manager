package org.makmini.manager;

import android.app.Activity;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String SOCK = "/dev/minisud.sock";
    private EditText cmdView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cmdView = findViewById(R.id.cmd);
        logView = findViewById(R.id.log);
        Button run = findViewById(R.id.run);
        Button status = findViewById(R.id.status);

        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());

        run.setOnClickListener(v -> execute(cmdView.getText().toString()));
        status.setOnClickListener(v -> execute("id; getenforce"));
    }

    private void execute(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCK, Namespace.FILESYSTEM));
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
}
