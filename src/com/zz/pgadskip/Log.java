package com.zz.pgadskip;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 只写到手机本地：/sdcard/Android/data/com.zz.pgadskip/files/log.txt，不联网。 */
final class Log {
    static File file;
    static final Handler h = new Handler(Looper.getMainLooper());
    static long lastDump = 0;
    static final long MAX = 4L * 1024 * 1024;

    static void init(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir != null) file = new File(dir, "log.txt");
    }

    static synchronized void w(String s) {
        android.util.Log.i("PGAdSkip", s);
        if (file == null) return;
        try {
            if (file.length() > MAX) {
                File old = new File(file.getParent(), "log.old.txt");
                old.delete();
                file.renameTo(old);
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(new Date()) + " " + s + "\n";
            try (FileOutputStream o = new FileOutputStream(file, true)) { o.write(line.getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { }
    }

    /** 记录模式：页面切换后 400ms 把胖乖的界面结构写进日志，用来补规则。 */
    static void dumpLater(AccessibilityService s, String why) {
        h.postDelayed(() -> dump(s, why), 400);
    }

    static void dump(AccessibilityService s, String why) {
        long now = System.currentTimeMillis();
        if (now - lastDump < 300) return;
        lastDump = now;
        StringBuilder sb = new StringBuilder("DUMP ").append(why).append('\n');
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null || !AdSkipService.TARGET.contentEquals(r.getPackageName())) continue;
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                sb.append("  WIN type=").append(w.getType()).append(' ').append(b.toShortString()).append('\n');
                walk(r, 2, sb, new int[]{0});
            }
        } catch (Throwable t) { sb.append("  dump error ").append(t).append('\n'); }
        w(sb.toString());
    }

    static void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || count[0]++ > 600 || depth > 40) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        String id = AdSkipService.idOf(n);
        CharSequence t = n.getText(), d = n.getContentDescription();
        CharSequence c = n.getClassName();
        if (!id.isEmpty() || t != null || d != null || n.isClickable()) {
            for (int i = 0; i < depth; i++) sb.append(' ');
            sb.append(c == null ? "?" : c.toString().replaceFirst("^android\\.(widget|view)\\.", ""));
            if (!id.isEmpty()) sb.append(" #").append(id);
            if (t != null) sb.append(" \"").append(cut(t)).append('"');
            if (d != null) sb.append(" desc=\"").append(cut(d)).append('"');
            if (n.isClickable()) sb.append(" [click]");
            if (!n.isVisibleToUser()) sb.append(" [hidden]");
            sb.append(' ').append(b.toShortString()).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, sb, count);
    }

    static String cut(CharSequence s) {
        String x = s.toString().replace('\n', ' ');
        return x.length() > 40 ? x.substring(0, 40) + "…" : x;
    }
}
