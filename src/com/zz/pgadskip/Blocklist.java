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
 * 拦截名单：/sdcard/Android/data/com.zz.pgadskip/files/blocklist.txt，一行一个域名，
 * 写 a.com 就同时拦 a.com 和 *.a.com；# 开头是注释。改了文件 5 秒内生效，不用重装。
 * 只对胖乖生效（VPN 只收胖乖的流量），所以拦抖音、快手的域名不影响别的 app。
 */
final class Blocklist {
    static volatile Set<String> rules = new HashSet<>();
    static File file;
    static long mtime = -1, lastCheck = 0;

    static final String DEFAULT =
            "# 胖乖去广告 拦截名单（只对胖乖生效）\n"
            + "# 一行一个域名，写 a.com 同时拦 a.com 和 *.a.com\n"
            + "# 千万别加：qiekj.com（胖乖自己）、weixin.qq.com / tenpay.com / alipay.com（付款）\n"
            + "\n# 穿山甲 / GroMore\n"
            + "pangolin-sdk-toutiao.com\npangolin-sdk-toutiao1.com\npangolin-sdk-toutiao-b.com\npglstatp-toutiao.com\n"
            + "pangolin.snssdk.com\nad.oceanengine.com\n"
            + "\n# 优量汇（腾讯广告）\n"
            + "gdt.qq.com\ne.qq.com\ngdtimg.com\npgdt.gtimg.cn\nugdtimg.com\n"
            + "\n# 快手联盟\n"
            + "adukwai.com\ne.kuaishou.com\n"
            + "\n# 百度百青藤\n"
            + "mobads.baidu.com\nmobads-logs.baidu.com\ncpro.baidustatic.com\ncpro.baidu.com\npos.baidu.com\n"
            + "\n# Sigmob / TopOn / 小米 / 爱奇艺 / 京东 / 其他聚合\n"
            + "sigmob.cn\nsigmob.com\ntoponad.com\ntoponadss.com\nanythinktech.com\n"
            + "ad.xiaomi.com\nadv.sec.miui.com\ncupid.iqiyi.com\nvlion.cn\nubixai.com\nyfanads.com\n";

    static synchronized void load(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        file = new File(dir, "blocklist.txt");
        try {
            if (!file.exists()) {
                try (FileOutputStream o = new FileOutputStream(file)) { o.write(DEFAULT.getBytes(StandardCharsets.UTF_8)); }
            }
            Set<String> s = new HashSet<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    int hash = line.indexOf('#');
                    if (hash >= 0) line = line.substring(0, hash).trim();
                    if (line.startsWith("*.")) line = line.substring(2);
                    if (!line.isEmpty()) s.add(line);
                }
            }
            rules = s;
            mtime = file.lastModified();
        } catch (Throwable t) { Log.w("blocklist load " + t); }
    }

    static void reloadIfChanged(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 5000) return;
        lastCheck = now;
        if (file == null || file.lastModified() != mtime) { load(c); Log.w("blocklist reloaded, rules=" + rules.size()); }
    }

    static int size() { return rules.size(); }

    static boolean blocked(String name) {
        Set<String> r = rules;
        String n = name;
        while (true) {
            if (r.contains(n)) return true;
            int dot = n.indexOf('.');
            if (dot < 0) return false;
            n = n.substring(dot + 1);
        }
    }
}
