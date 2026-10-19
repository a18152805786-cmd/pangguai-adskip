package com.zz.pgadskip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** 开机后把网络拦截重新开起来（前提：用户开过、系统授权还在、没有别的 VPN 在跑）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Log.init(c);
        if (Prefs.vpn(c) && VpnService.prepare(c) == null && !DnsVpnService.otherVpnActive(c)) DnsVpnService.start(c);
    }
}
