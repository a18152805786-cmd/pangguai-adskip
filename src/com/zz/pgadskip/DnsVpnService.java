package com.zz.pgadskip;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只管胖乖的本地 VPN：只有胖乖的 DNS 查询会进来（addAllowedApplication + 只路由假 DNS 地址），
 * 名单上的域名直接回「不存在」，其余转给手机原本的 DNS。别的 app 的流量完全不经过这里。
 * 胖乖的正常数据流量也不经过这里——只拦"查地址"这一步。
 */
public class DnsVpnService extends VpnService {
    static final String ACTION_STOP = "com.zz.pgadskip.STOP_VPN";
    static final String VPN_ADDR = "10.111.222.1";
    static final String DNS_ADDR = "10.111.222.2";
    static volatile boolean running = false;

    ParcelFileDescriptor tun;
    FileOutputStream out;
    Thread loop;
    ExecutorService pool;
    final HashMap<String, Long> logged = new HashMap<>();

    static void start(Context c) {
        try { c.startService(new Intent(c, DnsVpnService.class)); }
        catch (Throwable t) { Log.w("vpn start failed " + t); }
    }

    static void stop(Context c) {
        try { c.startService(new Intent(c, DnsVpnService.class).setAction(ACTION_STOP)); }
        catch (Throwable t) { Log.w("vpn stop failed " + t); }
    }

    /** 手机上已经有别的 VPN（比如 Clash）在跑：不抢，免得把它顶掉。 */
    static boolean otherVpnActive(Context c) {
        if (running) return false;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.init(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { shutdown("stop requested"); stopSelf(); return START_NOT_STICKY; }
        if (!running) startTun();
        return START_STICKY;
    }

    void startTun() {
        try {
            Blocklist.load(this);
            Builder b = new Builder()
                    .setSession("胖乖去广告")
                    .addAddress(VPN_ADDR, 32)
                    .addDnsServer(DNS_ADDR)
                    .addRoute(DNS_ADDR, 32)
                    .setBlocking(true);
            b.addAllowedApplication(AdSkipService.TARGET);
            tun = b.establish();
            if (tun == null) { Log.w("vpn not prepared"); return; }
            out = new FileOutputStream(tun.getFileDescriptor());
            pool = Executors.newFixedThreadPool(4);
            running = true;
            loop = new Thread(this::readLoop, "dns-vpn");
            loop.start();
            Log.w("vpn up, rules=" + Blocklist.size());
        } catch (Throwable t) {
            Log.w("vpn up failed " + t);
            shutdown("failed");
        }
    }

    void readLoop() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                byte[] p = new byte[n];
                System.arraycopy(buf, 0, p, 0, n);
                try { handle(p); } catch (Throwable t) { Log.w("dns handle " + t); }
            }
        } catch (Throwable t) {
            if (running) Log.w("vpn loop ended " + t);
        }
    }

    void handle(byte[] p) {
        if ((p[0] >> 4) != 4 || p[9] != 17) return;          // 只处理 IPv4 UDP
        int ihl = (p[0] & 0x0F) * 4;
        int total = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        int dstPort = ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
        if (dstPort != 53 || total > p.length) return;
        int off = ihl + 8;
        byte[] q = new byte[total - off];
        System.arraycopy(p, off, q, 0, q.length);
        String name = Dns.qname(q);
        if (name == null) return;

        Blocklist.reloadIfChanged(this);
        boolean block = Blocklist.blocked(name);
        logOnce(name, block);
        if (block) {
            write(p, ihl, Dns.nxdomain(q));
        } else {
            pool.execute(() -> {
                byte[] r = forward(q);
                if (r != null) write(p, ihl, r);
            });
        }
    }

    byte[] forward(byte[] q) {
        for (InetAddress dns : upstreams()) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);                      // 自己的转发不能再进 VPN
                s.setSoTimeout(2500);
                s.send(new DatagramPacket(q, q.length, dns, 53));
                byte[] r = new byte[4096];
                DatagramPacket rp = new DatagramPacket(r, r.length);
                s.receive(rp);
                byte[] res = new byte[rp.getLength()];
                System.arraycopy(r, 0, res, 0, res.length);
                return res;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 手机当前网络（Wi-Fi/流量）的 DNS，再加阿里、腾讯公共 DNS 兜底。 */
    List<InetAddress> upstreams() {
        List<InetAddress> list = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) for (InetAddress a : lp.getDnsServers()) {
                String h = a.getHostAddress();
                if (h != null && !h.equals(DNS_ADDR)) list.add(a);
            }
            list.add(InetAddress.getByName("223.5.5.5"));
            list.add(InetAddress.getByName("119.29.29.29"));
        } catch (Throwable ignored) { }
        return list;
    }

    /** 把 DNS 回答包成 IPv4/UDP 包写回给胖乖：源/目的地址和端口对调。 */
    void write(byte[] req, int ihl, byte[] dns) {
        byte[] p = new byte[20 + 8 + dns.length];
        p[0] = 0x45; p[8] = 64; p[9] = 17;
        int len = p.length;
        p[2] = (byte) (len >> 8); p[3] = (byte) len;
        System.arraycopy(req, 16, p, 12, 4);   // src = 原来的 dst
        System.arraycopy(req, 12, p, 16, 4);   // dst = 原来的 src
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        sum = ~sum & 0xFFFF;
        p[10] = (byte) (sum >> 8); p[11] = (byte) sum;
        p[20] = req[ihl + 2]; p[21] = req[ihl + 3];   // src port = 53
        p[22] = req[ihl];     p[23] = req[ihl + 1];   // dst port = 原来的源端口
        int ulen = 8 + dns.length;
        p[24] = (byte) (ulen >> 8); p[25] = (byte) ulen;   // UDP 校验和填 0，IPv4 允许
        System.arraycopy(dns, 0, p, 28, dns.length);
        synchronized (this) {
            try { if (running) out.write(p); } catch (Throwable ignored) { }
        }
    }

    /** 同一个域名 15 秒记一次，用来看胖乖连了哪些地方、名单要不要补。 */
    void logOnce(String name, boolean block) {
        long now = System.currentTimeMillis();
        Long last = logged.get(name);
        if (last != null && now - last < 15000) return;
        logged.put(name, now);
        Log.w("DNS " + (block ? "BLOCK " : "allow ") + name);
    }

    void shutdown(String why) {
        boolean was = running;
        running = false;
        try { if (tun != null) tun.close(); } catch (Throwable ignored) { }
        tun = null;
        if (pool != null) pool.shutdownNow();
        if (was) Log.w("vpn down: " + why);
    }

    @Override
    public void onRevoke() {   // 被别的 VPN（比如 Clash）顶掉，或者用户在系统里断开
        shutdown("revoked");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown("destroyed");
        super.onDestroy();
    }
}
