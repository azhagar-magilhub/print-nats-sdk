package com.magilhub.printnats.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import com.google.gson.JsonParser;
import com.magilhub.printnats.PrintNats;
import com.magilhub.printnats.PrintNatsConfig;
import com.magilhub.printnats.android.PrintNatsAndroid;
import com.magilhub.printnats.android.service.PrintNatsService;
import com.magilhub.printnats.queue.PrintJob;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Manual test bench for the Android adapter: configure, print a test KOT, watch job/status/connection events. */
public class MainActivity extends Activity {
    private TextView log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final EditText config = findViewById(R.id.config);
        log = findViewById(R.id.log);
        PrintNatsConfig saved = PrintNatsAndroid.savedConfig(this);
        config.setText(saved != null ? saved.toJson() : asset("sample-config.json"));

        PrintNatsAndroid.setListener(new PrintNats.Listener() {
            @Override
            public void onJobEvent(PrintJob job, String event) {
                append("JOB " + event + " " + job);
            }

            @Override
            public void onStatusEvent(String subject, byte[] data, boolean history) {
                append((history ? "HIST " : "STATUS ") + new String(data, StandardCharsets.UTF_8));
            }

            @Override
            public void onConnectionEvent(String type, String detail) {
                append("NATS " + type + " " + (detail == null ? "" : detail));
            }
        });

        findViewById(R.id.start).setOnClickListener(v -> {
            try {
                PrintNatsAndroid.configure(this, PrintNatsConfig.fromJson(config.getText().toString()));
                PrintNatsService.start(this);
                append("configured + service started");
            } catch (Exception e) {
                append("configure failed: " + e);
            }
        });
        findViewById(R.id.kot).setOnClickListener(v -> run(() -> {
            PrintNats sdk = PrintNatsAndroid.get(this);
            int n = sdk.printKot(JsonParser.parseString(asset("sample-order.json")).getAsJsonObject(), null, false);
            append("test KOT queued, tickets=" + n);
        }));
        findViewById(R.id.failed).setOnClickListener(v -> run(() -> {
            for (PrintJob j : PrintNatsAndroid.get(this).failedJobs()) append("FAILED " + j);
        }));
        findViewById(R.id.stop).setOnClickListener(v -> {
            PrintNatsService.stop(this);
            PrintNatsAndroid.stop();
            append("stopped");
        });
    }

    private void run(Runnable r) {
        new Thread(() -> {
            try {
                r.run();
            } catch (Exception e) {
                append("error: " + e);
            }
        }).start();
    }

    private void append(final String line) {
        final String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        runOnUiThread(() -> log.append(stamp + " " + line + "\n"));
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }
}
