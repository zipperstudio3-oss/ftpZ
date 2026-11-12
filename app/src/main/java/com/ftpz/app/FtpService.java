package com.ftpz.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Foreground service that keeps the FTP server alive while the screen is off. */
public class FtpService extends Service {

    public static final String ACTION_STOP = "com.ftpz.app.STOP";
    private static final String CHANNEL_ID = "ftpz_server";
    private static final int NOTIF_ID = 21;
    private static final int MAX_LOG = 150;

    public interface Callback {
        void onStateChanged();

        void onLog();
    }

    // ---- state shared with the UI (process-wide, main-thread notified) ----
    public static volatile Callback callback;
    public static volatile FtpServer server;
    public static volatile boolean running;
    public static volatile String lastError;
    public static volatile String address = "";

    private static final LinkedList<String> LOG = new LinkedList<String>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static synchronized List<String> getLogs() {
        return new ArrayList<String>(LOG);
    }

    public static synchronized void clearLogs() {
        LOG.clear();
    }

    static void addLog(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (FtpService.class) {
            LOG.add(t + "|" + msg);
            while (LOG.size() > MAX_LOG) LOG.removeFirst();
        }
        notifyUi(false);
    }

    private static void notifyUi(final boolean state) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Callback cb = callback;
                if (cb == null) return;
                if (state) cb.onStateChanged();
                else cb.onLog();
            }
        });
    }

    // ---- instance ----
    private volatile boolean destroyed;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        SharedPreferences p = Prefs.get(this);
        int port = p.getInt(Prefs.KEY_PORT, 2121);
        String ip = NetUtil.getLocalIp();
        address = "ftp://" + (ip == null ? "0.0.0.0" : ip) + ":" + port;

        // Must be called quickly after startForegroundService().
        Notification n = buildNotification(address);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (server != null && server.isRunning()) return START_NOT_STICKY;

        destroyed = false;
        final int fPort = port;
        final File root = new File(p.getString(Prefs.KEY_ROOT, "/sdcard"));
        final String user = p.getString(Prefs.KEY_USER, "ftpz");
        final String pass = p.getString(Prefs.KEY_PASS, "");
        final boolean anon = p.getBoolean(Prefs.KEY_ANON, false);
        final boolean readOnly = p.getBoolean(Prefs.KEY_READONLY, false);

        // Network work stays off the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FtpServer s = new FtpServer(root, fPort, user, pass, anon, readOnly,
                            new FtpServer.Listener() {
                                @Override
                                public void onLog(String message) {
                                    addLog(message);
                                }

                                @Override
                                public void onClientsChanged(int count) {
                                    notifyUi(false);
                                }
                            });
                    s.start();
                    if (destroyed) {          // user pressed stop while we were starting
                        s.stop();
                        return;
                    }
                    server = s;
                    running = true;
                    lastError = null;
                    acquireLocks();
                    notifyUi(true);
                } catch (Exception e) {
                    running = false;
                    server = null;
                    String m = e.getMessage();
                    lastError = (e instanceof java.net.BindException)
                            ? "Port " + fPort + " is busy. Pick another port."
                            : "Could not start: " + (m == null ? e.getClass().getSimpleName() : m);
                    addLog("✕ " + lastError);
                    notifyUi(true);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            stopSelf();
                        }
                    });
                }
            }
        }, "ftpz-start").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        FtpServer s = server;
        server = null;
        if (s != null) s.stop();
        running = false;
        releaseLocks();
        stopForeground(true);
        notifyUi(true);
        super.onDestroy();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ftpZ:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ftpZ:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String addr) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "FTP server",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while the ftpZ server is running");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        Intent stop = new Intent(this, FtpService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_ftp)
                .setContentTitle("ftpZ server is online")
                .setContentText(addr)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_ftp), "Stop", stopPi).build())
                .build();
    }
}
