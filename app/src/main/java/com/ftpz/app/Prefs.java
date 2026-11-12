package com.ftpz.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.security.SecureRandom;

/** Persistent settings shared by the activity and the service. */
public final class Prefs {

    public static final String KEY_PORT = "port";
    public static final String KEY_USER = "user";
    public static final String KEY_PASS = "pass";
    public static final String KEY_ROOT = "root";
    public static final String KEY_ANON = "anon";
    public static final String KEY_READONLY = "readonly";

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        SharedPreferences p = c.getSharedPreferences("ftpz", Context.MODE_PRIVATE);
        if (!p.contains(KEY_PASS)) {
            // first run: create sensible defaults with a random password
            p.edit()
                    .putInt(KEY_PORT, 2121)
                    .putString(KEY_USER, "ftpz")
                    .putString(KEY_PASS, randomPassword(6))
                    .putString(KEY_ROOT, Environment.getExternalStorageDirectory().getAbsolutePath())
                    .putBoolean(KEY_ANON, false)
                    .putBoolean(KEY_READONLY, false)
                    .apply();
        }
        return p;
    }

    private static String randomPassword(int len) {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789"; // no look-alike characters
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
