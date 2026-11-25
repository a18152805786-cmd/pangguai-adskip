package com.zz.pgadskip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** 开机后把网络拦截重新开起来（前提：用户开过、系统授权还在、没有别的 VPN 在跑）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        Log.init(c);
        // Android 15 起不能从开机广播启动 dataSync 前台服务，监测由使用者重新开启。
        if (LaundryStore.enabled(c)) {
            LaundryStore.sp(c).edit().putBoolean("watch", false).putString("error", "手机已重启，请重新开始监测").apply();
        }
        if (Prefs.vpn(c) && VpnService.prepare(c) == null && !DnsVpnService.otherVpnActive(c)) DnsVpnService.start(c);
        A11yRevive.ensure(c);
    }
}
