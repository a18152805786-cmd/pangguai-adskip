package com.zz.pgadskip;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return sp(c).getBoolean("enabled", true); }
    static boolean record(Context c) { return sp(c).getBoolean("record", true); }
    static boolean cover(Context c) { return sp(c).getBoolean("cover", true); }
    static boolean vpn(Context c) { return sp(c).getBoolean("vpn", false); }
    static boolean hideMall(Context c) { return sp(c).getBoolean("hideMall", true); }
    static boolean hidePoints(Context c) { return sp(c).getBoolean("hidePoints", true); }
    static void set(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
}

final class Stats {
    static void hit(Context c) {
        SharedPreferences s = Prefs.sp(c);
        s.edit().putInt("hits", s.getInt("hits", 0) + 1).apply();
    }
    static int hits(Context c) { return Prefs.sp(c).getInt("hits", 0); }
}
