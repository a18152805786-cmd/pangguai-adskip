package com.zz.pgadskip;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * 系统杀掉本 app 进程后，无障碍服务会被记成「已崩溃」，HyperOS 不会再自动连回来，
 * 于是弹窗、挡板全部失效，只剩网络拦截（11-25 18:40 实际发生过）。
 * 这里在胖乖有网络动静时检查一下：服务没连着、但用户在设置里是开着的，就把它关了再开，系统会重新连上。
 * 需要 WRITE_SECURE_SETTINGS（只能用 adb 授一次：pm grant com.zz.pgadskip android.permission.WRITE_SECURE_SETTINGS），
 * 没有这个权限就什么也不做。用户自己在设置里关掉的不会被重新打开。
 */
final class A11yRevive {
    private A11yRevive() { }

    private static volatile long triedAt = -600000;

    static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static void ensure(Context c) {
        if (AdSkipService.alive) return;
        long now = SystemClock.uptimeMillis();
        if (now - triedAt < 60000) return;          // 一分钟最多试一次
        triedAt = now;
        if (!canWrite(c)) return;
        try {
            ContentResolver cr = c.getContentResolver();
            String me = new ComponentName(c, AdSkipService.class).flattenToString();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || !containsMe(cur, me)) return;   // 用户自己关掉的，不动
            StringBuilder without = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.isEmpty() || s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortName(c))) continue;
                if (without.length() > 0) without.append(':');
                without.append(s);
            }
            Log.w("a11y not connected, re-enabling");
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without.toString());
            final Context app = c.getApplicationContext();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur);
                    Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (Throwable t) { Log.w("a11y re-enable failed " + t); }
            }, 800);
        } catch (Throwable t) { Log.w("a11y revive failed " + t); }
    }

    private static String shortName(Context c) {
        return c.getPackageName() + "/.AdSkipService";
    }

    private static boolean containsMe(String cur, String me) {
        for (String s : cur.split(":")) if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase("com.zz.pgadskip/.AdSkipService")) return true;
        return false;
    }
}
