package com.ftpz.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

public class MainActivity extends Activity implements FtpService.Callback {

    private static final int REQ_STORAGE = 11;
    private static final int REQ_NOTIF = 12;

    private SharedPreferences prefs;

    private ImageView powerButton;
    private View ring1, ring2, statusDot;
    private TextView statusText, addressText, hintText;
    private TextView statClients, statSent, statReceived;
    private TextView btnShowPass, btnClearLog, logText, warnAnon;
    private EditText editPort, editUser, editPass, editRoot;
    private Switch switchAnon, switchReadonly;
    private View[] lockables;
    private ValueAnimator pulse1, pulse2;
    private boolean passVisible;
    private boolean waitingForStorage;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateStats();
            handler.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);
        bindViews();
        loadPrefs();
        setupListeners();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FtpService.callback = this;
        updateUi();
        renderLog();
        handler.post(ticker);
        if (waitingForStorage && hasStoragePermission()) {
            waitingForStorage = false;
            tryStart();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (FtpService.callback == this) FtpService.callback = null;
        stopPulse();
        saveSoft();
    }

    // ------------------------------------------------------------------ setup

    private void bindViews() {
        powerButton = findViewById(R.id.powerButton);
        ring1 = findViewById(R.id.ring1);
        ring2 = findViewById(R.id.ring2);
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        addressText = findViewById(R.id.addressText);
        hintText = findViewById(R.id.hintText);
        statClients = findViewById(R.id.statClients);
        statSent = findViewById(R.id.statSent);
        statReceived = findViewById(R.id.statReceived);
        editPort = findViewById(R.id.editPort);
        editUser = findViewById(R.id.editUser);
        editPass = findViewById(R.id.editPass);
        editRoot = findViewById(R.id.editRoot);
        btnShowPass = findViewById(R.id.btnShowPass);
        btnClearLog = findViewById(R.id.btnClearLog);
        logText = findViewById(R.id.logText);
        warnAnon = findViewById(R.id.warnAnon);
        switchAnon = findViewById(R.id.switchAnon);
        switchReadonly = findViewById(R.id.switchReadonly);
        lockables = new View[]{editPort, editUser, editPass, editRoot, switchAnon, switchReadonly,
                findViewById(R.id.chipStorage), findViewById(R.id.chipDownload), findViewById(R.id.chipDcim),
                findViewById(R.id.chipDocuments), findViewById(R.id.chipPictures), findViewById(R.id.chipMusic)};
    }

    private void loadPrefs() {
        editPort.setText(String.valueOf(prefs.getInt(Prefs.KEY_PORT, 2121)));
        editUser.setText(prefs.getString(Prefs.KEY_USER, "ftpz"));
        editPass.setText(prefs.getString(Prefs.KEY_PASS, ""));
        editRoot.setText(prefs.getString(Prefs.KEY_ROOT, Environment.getExternalStorageDirectory().getAbsolutePath()));
        switchAnon.setChecked(prefs.getBoolean(Prefs.KEY_ANON, false));
        switchReadonly.setChecked(prefs.getBoolean(Prefs.KEY_READONLY, false));
        applyAnonState();
    }

    private void setupListeners() {
        powerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bounce(v);
                if (FtpService.running) stopServer();
                else tryStart();
            }
        });

        addressText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("ftpZ", currentAddress()));
                Toast.makeText(MainActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
            }
        });

        btnShowPass.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                passVisible = !passVisible;
                int sel = editPass.getText().length();
                editPass.setInputType(InputType.TYPE_CLASS_TEXT | (passVisible
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                editPass.setTypeface(Typeface.DEFAULT);
                editPass.setSelection(sel);
                btnShowPass.setText(passVisible ? R.string.hide : R.string.show);
            }
        });

        btnClearLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FtpService.clearLogs();
                renderLog();
            }
        });

        switchAnon.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                applyAnonState();
            }
        });

        editPort.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateAddress();
            }
        });

        File ext = Environment.getExternalStorageDirectory();
        bindChip(R.id.chipStorage, ext);
        bindChip(R.id.chipDownload, new File(ext, "Download"));
        bindChip(R.id.chipDcim, new File(ext, "DCIM"));
        bindChip(R.id.chipDocuments, new File(ext, "Documents"));
        bindChip(R.id.chipPictures, new File(ext, "Pictures"));
        bindChip(R.id.chipMusic, new File(ext, "Music"));
    }

    private void bindChip(int id, final File dir) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editRoot.setText(dir.getAbsolutePath());
            }
        });
    }

    // ------------------------------------------------------------------ start / stop

    private void tryStart() {
        if (!validateAndSave()) return;
        if (!hasStoragePermission()) {
            requestStorage();
            return;
        }
        if (NetUtil.getLocalIp() == null) {
            Toast.makeText(this, R.string.err_wifi, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, FtpService.class);
        startForegroundService(i);
    }

    private void stopServer() {
        stopService(new Intent(this, FtpService.class));
    }

    private boolean validateAndSave() {
        int port;
        try {
            port = Integer.parseInt(editPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1024 || port > 65535) {
            editPort.setError(getString(R.string.err_port));
            editPort.requestFocus();
            return false;
        }
        boolean anon = switchAnon.isChecked();
        String user = editUser.getText().toString().trim();
        String pass = editPass.getText().toString();
        if (!anon) {
            if (user.length() == 0) {
                editUser.setError(getString(R.string.err_user));
                editUser.requestFocus();
                return false;
            }
            if (pass.length() == 0) {
                editPass.setError(getString(R.string.err_pass));
                editPass.requestFocus();
                return false;
            }
        }
        String root = editRoot.getText().toString().trim();
        if (root.length() == 0 || !new File(root).isDirectory()) {
            editRoot.setError(getString(R.string.err_root));
            editRoot.requestFocus();
            return false;
        }
        prefs.edit()
                .putInt(Prefs.KEY_PORT, port)
                .putString(Prefs.KEY_USER, user)
                .putString(Prefs.KEY_PASS, pass)
                .putString(Prefs.KEY_ROOT, root)
                .putBoolean(Prefs.KEY_ANON, anon)
                .putBoolean(Prefs.KEY_READONLY, switchReadonly.isChecked())
                .apply();
        return true;
    }

    /** Saves whatever is typed without validating, so settings survive leaving the app. */
    private void saveSoft() {
        if (FtpService.running) return;
        SharedPreferences.Editor e = prefs.edit();
        try {
            int p = Integer.parseInt(editPort.getText().toString().trim());
            if (p >= 1024 && p <= 65535) e.putInt(Prefs.KEY_PORT, p);
        } catch (NumberFormatException ignored) {
        }
        String u = editUser.getText().toString().trim();
        if (u.length() > 0) e.putString(Prefs.KEY_USER, u);
        String pw = editPass.getText().toString();
        if (pw.length() > 0) e.putString(Prefs.KEY_PASS, pw);
        String r = editRoot.getText().toString().trim();
        if (r.length() > 0) e.putString(Prefs.KEY_ROOT, r);
        e.putBoolean(Prefs.KEY_ANON, switchAnon.isChecked());
        e.putBoolean(Prefs.KEY_READONLY, switchReadonly.isChecked());
        e.apply();
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(R.string.perm_title)
                    .setMessage(R.string.perm_msg)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.allow, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            waitingForStorage = true;
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (ActivityNotFoundException e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        }
                    })
                    .show();
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) tryStart();
            else Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ UI state

    @Override
    public void onStateChanged() {
        updateUi();
        updateStats();
        String err = FtpService.lastError;
        if (err != null && !FtpService.running) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            FtpService.lastError = null;
        }
        renderLog();
    }

    @Override
    public void onLog() {
        renderLog();
        updateStats();
    }

    private void updateUi() {
        boolean on = FtpService.running;
        powerButton.setBackgroundResource(on ? R.drawable.bg_power_on : R.drawable.bg_power_off);
        int pad = dp(44);
        powerButton.setPadding(pad, pad, pad, pad);
        statusDot.setBackgroundResource(on ? R.drawable.dot_on : R.drawable.dot_off);
        statusText.setText(on ? R.string.status_on : R.string.status_off);
        statusText.setTextColor(getColor(on ? R.color.ok : R.color.muted));
        hintText.setText(on ? R.string.hint_running : R.string.hint_start);
        updateAddress();

        for (View v : lockables) {
            v.setEnabled(!on);
            v.setAlpha(on ? 0.5f : 1f);
        }
        if (!on) applyAnonState();

        if (on) startPulse();
        else stopPulse();
    }

    private String currentAddress() {
        String ip = NetUtil.getLocalIp();
        if (FtpService.running && FtpService.address.length() > 0) return FtpService.address;
        if (ip == null) return getString(R.string.address_placeholder);
        String port = editPort.getText().toString().trim();
        if (port.length() == 0) port = String.valueOf(prefs.getInt(Prefs.KEY_PORT, 2121));
        return "ftp://" + ip + ":" + port;
    }

    private void updateAddress() {
        addressText.setText(currentAddress());
        addressText.setAlpha(FtpService.running ? 1f : 0.6f);
        if (!FtpService.running && NetUtil.getLocalIp() == null) {
            hintText.setText(R.string.err_wifi);
        }
    }

    private void applyAnonState() {
        boolean anon = switchAnon.isChecked();
        warnAnon.setVisibility(anon ? View.VISIBLE : View.GONE);
        if (FtpService.running) return;
        editUser.setEnabled(!anon);
        editPass.setEnabled(!anon);
        editUser.setAlpha(anon ? 0.4f : 1f);
        editPass.setAlpha(anon ? 0.4f : 1f);
    }

    private void updateStats() {
        FtpServer s = FtpService.server;
        statClients.setText(String.valueOf(s == null ? 0 : s.getClientCount()));
        statSent.setText(FtpServer.formatSize(s == null ? 0 : s.getBytesSent()));
        statReceived.setText(FtpServer.formatSize(s == null ? 0 : s.getBytesReceived()));
    }

    private void renderLog() {
        List<String> logs = FtpService.getLogs();
        if (logs.isEmpty()) {
            logText.setText(R.string.log_empty);
            logText.setTextColor(getColor(R.color.muted));
            return;
        }
        logText.setTextColor(getColor(R.color.text));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int from = Math.max(0, logs.size() - 40);
        for (int i = logs.size() - 1; i >= from; i--) {
            String[] parts = logs.get(i).split("\\|", 2);
            String time = parts[0];
            String msg = parts.length > 1 ? parts[1] : "";
            int start = sb.length();
            sb.append(time).append("  ");
            sb.setSpan(new ForegroundColorSpan(getColor(R.color.muted)), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int ms = sb.length();
            sb.append(msg);
            sb.setSpan(new ForegroundColorSpan(colorFor(msg)), ms, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i > from) sb.append('\n');
        }
        logText.setText(sb);
    }

    private int colorFor(String msg) {
        if (msg.length() == 0) return getColor(R.color.text);
        switch (msg.charAt(0)) {
            case '+':
                return getColor(R.color.accent);
            case '↑':
                return 0xFFA78BFA;
            case '↓':
            case '✓':
                return getColor(R.color.ok);
            case '✕':
                return getColor(R.color.err);
            default:
                return getColor(R.color.text);
        }
    }

    // ------------------------------------------------------------------ animation

    private void startPulse() {
        if (pulse1 != null && pulse1.isRunning()) return;
        pulse1 = makePulse(ring1, 0);
        pulse2 = makePulse(ring2, 1200);
        pulse1.start();
        pulse2.start();
    }

    private void stopPulse() {
        if (pulse1 != null) pulse1.cancel();
        if (pulse2 != null) pulse2.cancel();
        pulse1 = null;
        pulse2 = null;
        for (View r : new View[]{ring1, ring2}) {
            r.setAlpha(0f);
            r.setScaleX(1f);
            r.setScaleY(1f);
        }
    }

    private ValueAnimator makePulse(final View ring, long delay) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(2400);
        a.setStartDelay(delay);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                float f = (Float) va.getAnimatedValue();
                float s = 1f + 0.62f * f;
                ring.setScaleX(s);
                ring.setScaleY(s);
                ring.setAlpha(0.6f * (1f - f));
            }
        });
        return a;
    }

    private void bounce(final View v) {
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction(new Runnable() {
            @Override
            public void run() {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
