package com.zz.pgadskip;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LaundryMonitorService extends Service {
    private static final String PROGRESS = "laundry_monitor", ALERT = "laundry_available";
    private static final int NOTIFICATION = 1100;
    static volatile boolean running;
    private ScheduledExecutorService worker;
    private LaundryTransitions transitions;
    private PowerManager.WakeLock wakeLock;
    private String runId;
    private int failures;
    private boolean started;

    static boolean notificationsAllowed(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = nm.getNotificationChannel(ALERT);
        return nm.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE)
                && (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    static void start(Context c) {
        synchronized (LaundryStore.class) {
            if (running) throw new IllegalStateException("监测服务正在运行或停止，请稍后重试");
            LaundryStore.sp(c).edit().putBoolean("watch", true).putString("watch_id", UUID.randomUUID().toString())
                    .putLong("watch_started", System.currentTimeMillis()).commit();
        }
        try { c.startForegroundService(new Intent(c, LaundryMonitorService.class)); }
        catch (RuntimeException e) {
            LaundryStore.sp(c).edit().putBoolean("watch", false).apply();
            throw e;
        }
    }

    static void stop(Context c) {
        synchronized (LaundryStore.class) {
            LaundryStore.sp(c).edit().putBoolean("watch", false).putString("watch_id", UUID.randomUUID().toString()).commit();
        }
        c.stopService(new Intent(c, LaundryMonitorService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(PROGRESS, "洗衣机监测运行状态", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERT, "洗衣机空闲提醒", NotificationManager.IMPORTANCE_HIGH));
        Notification n = progress("正在连接胖乖生活…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, n);
        worker = Executors.newSingleThreadScheduledExecutor();
        transitions = new LaundryTransitions();
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stop(this);
            return START_NOT_STICKY;
        }
        if (!LaundryStore.enabled(this) || !LaundryStore.loggedIn(this) || LaundryStore.selected(this).isEmpty()) {
            stop(this);
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            runId = LaundryStore.sp(this).getString("watch_id", "");
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pgadskip:laundry");
            wakeLock.acquire(6L * 60 * 60 * 1000);
            worker.execute(this::poll);
        }
        return START_STICKY;
    }

    private boolean active(String epoch) {
        return LaundryStore.enabled(this) && epoch.equals(LaundryStore.epoch(this))
                && runId.equals(LaundryStore.sp(this).getString("watch_id", ""));
    }

    private void poll() {
        String epoch = LaundryStore.epoch(this);
        if (!active(epoch)) return;
        try {
            if (!notificationsAllowed(this)) {
                LaundryStore.error(this, epoch, "通知权限或提醒通道已关闭，请允许通知后重新开始");
                stop(this);
                return;
            }
            if (System.currentTimeMillis() - LaundryStore.sp(this).getLong("watch_started", System.currentTimeMillis()) >= 6L * 60 * 60 * 1000) {
                onTimeout(0, 0);
                return;
            }
            List<LaundryDevice> devices = new LaundryApi(this).recent(LaundryStore.token(this));
            synchronized (LaundryStore.class) {
                if (!active(epoch)) return;
                if (!LaundryStore.publish(this, epoch, devices)) throw new Exception("无法保存查询结果");
                Set<String> selected = LaundryStore.selected(this);
                for (LaundryDevice d : transitions.update(devices, selected)) available(d);
                int idle = 0, busy = 0, unknown = selected.size();
                for (LaundryDevice d : devices) if (selected.contains(d.id)) {
                    if (d.state == LaundryDevice.IDLE) idle++;
                    if (d.state == LaundryDevice.BUSY) busy++;
                    if (d.state != LaundryDevice.UNKNOWN) unknown--;
                }
                status("空闲 " + idle + " 台 · 使用中 " + busy + " 台" + (unknown > 0 ? " · 未知 " + unknown + " 台" : ""));
                failures = 0;
            }
        } catch (Exception e) {
            synchronized (LaundryStore.class) {
                if (!active(epoch)) return;
                String message = message(e);
                LaundryStore.error(this, epoch, message);
                failures = Math.min(failures + 1, 4);
                status("查询未成功，稍后重试；设备状态待核实");
                if (e instanceof LaundryApi.ApiException && ((LaundryApi.ApiException) e).auth) {
                    notice("login", "洗衣机监测已暂停", "登录失效或访问被拒绝，请打开应用重新登录");
                    stop(this);
                    return;
                }
            }
        }
        if (active(epoch) && !worker.isShutdown()) {
            long delay = Math.min(300, LaundryStore.interval(this) * (1L << failures));
            worker.schedule(this::poll, delay, TimeUnit.SECONDS);
        }
    }

    static String message(Exception e) {
        if (e instanceof LaundryApi.ApiException) return e.getMessage();
        if (e instanceof java.io.IOException) return "网络连接失败，设备状态待核实";
        if (e instanceof java.security.GeneralSecurityException) return "登录信息不可读，请重新登录";
        return "查询未完成，请稍后重试或重新登录";
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(this, 11, new Intent(this, LaundryActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification progress(String text) {
        PendingIntent stop = PendingIntent.getService(this, 12, new Intent(this, LaundryMonitorService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, PROGRESS).setSmallIcon(R.drawable.ic_laundry)
                .setContentTitle("洗衣机空闲监测").setContentText(text).setContentIntent(open())
                .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "停止监测", stop).build()).build();
    }

    private void status(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, progress(text));
    }

    private void available(LaundryDevice d) { notice(d.id, d.name + "已空闲", "现在可以使用。打开胖乖确认后再前往。"); }

    private void notice(String tag, String title, String text) {
        if (!notificationsAllowed(this)) return;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify("laundry:" + tag, 1101,
                new Notification.Builder(this, ALERT).setSmallIcon(R.drawable.ic_laundry).setContentTitle(title).setContentText(text)
                        .setContentIntent(open()).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
    }

    @Override public void onTimeout(int startId, int fgsType) {
        LaundryStore.error(this, LaundryStore.epoch(this), "系统已结束本次后台监测，请打开应用重新开始");
        notice("timeout", "洗衣机监测已停止", "本次后台运行已到系统时限，请重新开始");
        stop(this);
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
