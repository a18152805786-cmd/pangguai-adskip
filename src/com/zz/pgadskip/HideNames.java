package com.zz.pgadskip;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 首页图标区要抹掉的图标名字：/sdcard/Android/data/com.zz.pgadskip/files/hide.txt，一行一个，
 * 写的是图标下面那行字（完全一致）。改了文件 5 秒内生效，不用重装。
 */
final class HideNames {
    static volatile Set<String> names = new HashSet<>();
    static File file;
    static long mtime = -1, lastCheck = 0;

    static final String DEFAULT =
            "# 首页图标区要抹掉的图标（写图标下面的字，一行一个；# 开头是注释）\n"
            + "0元喝水\n胖乖商城\n外卖神券\n抖音神券\n";

    static synchronized void load(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        file = new File(dir, "hide.txt");
        try {
            if (!file.exists()) {
                try (FileOutputStream o = new FileOutputStream(file)) { o.write(DEFAULT.getBytes(StandardCharsets.UTF_8)); }
            }
            Set<String> s = new HashSet<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) s.add(line);
                }
            }
            names = s;
            mtime = file.lastModified();
        } catch (Throwable t) { Log.w("hide.txt load " + t); }
    }

    static Set<String> get(Context c) {
        long now = System.currentTimeMillis();
        if (file == null || (now - lastCheck > 5000 && file.lastModified() != mtime)) {
            lastCheck = now;
            load(c);
        } else if (now - lastCheck > 5000) {
            lastCheck = now;
        }
        return names;
    }
}
