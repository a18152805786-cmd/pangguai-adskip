package com.zz.pgadskip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LaundryActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView account, status;
    private LinearLayout list;
    private Button login, logout, refresh, interval, monitor, test;
    private boolean busy;
    private volatile boolean destroyed;
    private String rendered = "";
    private int pendingAction;
    private long nextSmsAt;
    private final Runnable tick = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide();
        // 登录表单和设备列表不进入系统截屏或最近任务缩略图。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(40), dp(20), dp(24));
        TextView title = text(root, "洗衣机空闲监测");
        title.setTextSize(24);
        text(root, "选择最近用过的洗衣机，使用中变为空闲时通知你。首次查询只显示当前状态。");
        account = text(root, ""); account.setTextSize(17);
        login = button(root, "登录胖乖账号", this::showLogin);
        logout = button(root, "退出监测账号", () -> new AlertDialog.Builder(this).setMessage("退出后清除本机保存的监测会话和设备列表？")
                .setNegativeButton("取消", null).setPositiveButton("退出", (d, w) -> {
                    LaundryMonitorService.stop(this); LaundryStore.logout(this); rendered = ""; render();
                }).show());
        refresh = button(root, "读取最近用过的洗衣机", this::query);
        TextView choose = text(root, "选择监测设备"); choose.setTextSize(19);
        list = column(); root.addView(list);
        interval = button(root, "", this::chooseInterval);
        monitor = button(root, "开始监测", this::toggleMonitor);
        test = button(root, "测试通知", () -> withNotifications(2));
        status = text(root, ""); status.setTextSize(16);
        text(root, "监测时会显示常驻通知并增加耗电，可随时在通知中停止。请允许通知，并把本工具的省电策略设为「无限制」。单次最长6小时，手机重启或系统结束后台运行后需重新开始。\n\n"
                + "本版只查询最近用过的洗衣机。会话加密保存在本机，查询直接发送到胖乖；不上传页面或日志。设备缺失、网络失败均视为未知。剩余分钟数尚未验证，本版以设备状态为准。");
        button(root, "返回去广告设置", this::finish);
        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(dp(20), insets.getSystemWindowInsetTop() + dp(16), dp(20), insets.getSystemWindowInsetBottom() + dp(24));
            return insets;
        });
        scroll.addView(root); setContentView(scroll); scroll.requestApplyInsets();
        render();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(LinearLayout parent, String value) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(15); v.setPadding(0, dp(9), 0, dp(9)); parent.addView(v); return v;
    }
    private Button button(LinearLayout parent, String value, Runnable click) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setOnClickListener(v -> click.run()); parent.addView(b); return b;
    }
    private EditText input(LinearLayout parent, String hint, int type) {
        EditText e = new EditText(this); e.setHint(hint); e.setInputType(type); e.setSingleLine(true); e.setSaveEnabled(false);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        parent.addView(e); return e;
    }

    private interface Work { Object run() throws Exception; }
    private interface Result { void accept(Object result) throws Exception; }
    private void submit(Work work, Result done) {
        if (busy) return;
        busy = true; render();
        worker.execute(() -> {
            try {
                Object result = work.run();
                ui.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    try { done.accept(result); } catch (Exception e) { toast(friendly(e)); }
                    rendered = ""; render();
                });
            } catch (Exception e) {
                ui.post(() -> { if (!destroyed) { busy = false; toast(friendly(e)); render(); } });
            }
        });
    }

    private String friendly(Exception e) {
        if (e instanceof java.io.IOException) return "网络连接失败，请检查网络后重试";
        if (e instanceof org.json.JSONException) return "服务返回格式已变化，请稍后重试";
        if (e instanceof java.security.GeneralSecurityException) return "无法读取或保存登录信息，请重新登录";
        String m = e.getMessage(); return m == null || m.isEmpty() ? "操作未完成，请重试" : m.substring(0, Math.min(150, m.length()));
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void showLogin() {
        LinearLayout content = column(); content.setPadding(dp(20), 0, dp(20), 0);
        text(content, "使用已有胖乖账号。短信仅在你点击「发送验证码」后发送。手机号和验证码不会写入日志。");
        EditText phone = input(content, "手机号", InputType.TYPE_CLASS_PHONE);
        EditText code = input(content, "短信验证码", InputType.TYPE_CLASS_NUMBER);
        Button send = button(content, "发送验证码", () -> { });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("登录胖乖账号").setView(content)
                .setNegativeButton("取消", null).setNeutralButton("已有 Token 登录", null).setPositiveButton("登录", null).create();
        dialog.setOnShowListener(d -> {
            send.setOnClickListener(v -> {
                String number = phone.getText().toString().trim();
                if (!number.matches("1[0-9]{10}")) { phone.setError("请输入11位手机号"); return; }
                long remain = nextSmsAt - android.os.SystemClock.elapsedRealtime();
                if (remain > 0) { toast("请在 " + ((remain + 999) / 1000) + " 秒后重试"); return; }
                if (busy) return;
                send.setEnabled(false); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                submit(() -> {
                    new LaundryApi(this).sendCode(number); return null;
                }, result -> {
                    nextSmsAt = android.os.SystemClock.elapsedRealtime() + 60000;
                    if (dialog.isShowing()) { send.setText("验证码已发送（60秒后可重试）"); toast("请在手机上查看短信并填写验证码"); }
                });
                // 失败和成功都会由主线程刷新按钮；限流使用独立时间戳检查。
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!dialog.isShowing() || destroyed) return;
                        send.setEnabled(!busy);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
                        if (busy) ui.postDelayed(this, 300);
                    }
                }, 300);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { if (!busy) { dialog.dismiss(); showTokenLogin(); } });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String number = phone.getText().toString().trim(), verify = code.getText().toString().trim();
                if (!number.matches("1[0-9]{10}")) { phone.setError("请输入11位手机号"); return; }
                if (!verify.matches("[0-9]{4,8}")) { code.setError("请输入短信验证码"); return; }
                String epoch = LaundryStore.epoch(this);
                submit(() -> {
                    LaundryApi api = new LaundryApi(this);
                    String token = api.login(number, verify);
                    return new Session(token, api.recent(token));
                }, result -> {
                    if (!dialog.isShowing() || !epoch.equals(LaundryStore.epoch(this))) return;
                    finishLogin((Session) result, "尾号 " + number.substring(7));
                    phone.setText(""); code.setText(""); dialog.dismiss();
                });
            });
        });
        dialog.show();
    }

    private static final class Session {
        final String token; final List<LaundryDevice> devices;
        Session(String token, List<LaundryDevice> devices) { this.token = token; this.devices = devices; }
    }
    private void showTokenLogin() {
        LinearLayout content = column(); content.setPadding(dp(20), 0, dp(20), 0);
        text(content, "仅粘贴你自己账号的有效 Token。先查询验证，成功后加密保存；不会显示或写入日志。");
        EditText token = input(content, "Token", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("已有 Token 登录").setView(content)
                .setNegativeButton("取消", null).setPositiveButton("验证并登录", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = token.getText().toString().trim();
            if (value.isEmpty() || value.contains("\n") || value.length() > 4096) { token.setError("请填写有效 Token"); return; }
            String epoch = LaundryStore.epoch(this);
            submit(() -> new Session(value, new LaundryApi(this).recent(value)), result -> {
                if (!dialog.isShowing() || !epoch.equals(LaundryStore.epoch(this))) return;
                finishLogin((Session) result, "Token 登录"); token.setText(""); dialog.dismiss();
            });
        }));
        dialog.show();
    }

    private void finishLogin(Session session, String label) throws Exception {
        LaundryMonitorService.stop(this);
        LaundryStore.login(this, session.token, label);
        LaundryStore.publish(this, LaundryStore.epoch(this), session.devices);
        Set<String> selected = new HashSet<>();
        for (LaundryDevice d : session.devices) selected.add(d.id);
        LaundryStore.sp(this).edit().putStringSet("selected", selected).apply();
        toast("已登录，请确认要监测的设备后开始");
    }

    private void query() {
        String epoch = LaundryStore.epoch(this);
        submit(() -> {
            try { return new LaundryApi(this).recent(LaundryStore.token(this)); }
            catch (Exception e) { LaundryStore.error(this, epoch, friendly(e)); throw e; }
        }, result -> {
            @SuppressWarnings("unchecked") List<LaundryDevice> devices = (List<LaundryDevice>) result;
            LaundryStore.publish(this, epoch, devices);
        });
    }

    private void chooseInterval() {
        int[] seconds = {30, 60, 120, 300};
        String[] labels = {"30秒", "60秒（推荐）", "2分钟", "5分钟"};
        int selected = 1; for (int i = 0; i < seconds.length; i++) if (seconds[i] == LaundryStore.interval(this)) selected = i;
        new AlertDialog.Builder(this).setTitle("查询间隔").setSingleChoiceItems(labels, selected, (d, which) -> {
            LaundryStore.sp(this).edit().putInt("interval", seconds[which]).apply(); d.dismiss(); render();
        }).setNegativeButton("取消", null).show();
    }

    private void toggleMonitor() {
        if (LaundryStore.enabled(this)) { LaundryMonitorService.stop(this); rendered = ""; render(); return; }
        if (LaundryStore.selected(this).isEmpty()) { toast("请先读取并选择至少一台洗衣机"); return; }
        withNotifications(1);
    }

    private void withNotifications(int action) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 51); return;
        }
        if (!LaundryMonitorService.notificationsAllowed(this)) {
            new AlertDialog.Builder(this).setMessage("需要允许通知才能发送空闲提醒。请在系统设置中打开通知及「洗衣机空闲提醒」。")
                    .setNegativeButton("取消", null).setPositiveButton("打开通知设置", (d, w) -> startActivity(
                            new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))).show(); return;
        }
        if (action == 1) {
            try { LaundryMonitorService.start(this); }
            catch (RuntimeException e) { toast("后台服务未能启动，请重新打开应用后再试"); }
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("laundry_available", "洗衣机空闲提醒", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(this, 13, new Intent(this, LaundryActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify("laundry:test", 1101, new Notification.Builder(this, "laundry_available").setSmallIcon(R.drawable.ic_laundry)
                    .setContentTitle("洗衣机提醒测试").setContentText("这是一条测试通知，表示空闲提醒通道可用。")
                    .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
            toast("测试通知已发送，请查看通知栏");
        }
        rendered = ""; render();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 51) {
            int action = pendingAction; pendingAction = 0;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && action > 0) withNotifications(action);
            else toast("未允许通知，监测尚未开启");
        }
    }

    private void render() {
        if (account == null || destroyed) return;
        boolean hasAccount = LaundryStore.loggedIn(this), watching = LaundryStore.enabled(this);
        account.setText(hasAccount ? "已登录 · " + LaundryStore.sp(this).getString("account", "") : "尚未登录胖乖账号");
        login.setVisibility(hasAccount ? View.GONE : View.VISIBLE); login.setEnabled(!busy);
        logout.setVisibility(hasAccount ? View.VISIBLE : View.GONE); logout.setEnabled(!busy && !watching);
        refresh.setEnabled(hasAccount && !busy);
        interval.setText("查询间隔：" + LaundryStore.interval(this) + " 秒"); interval.setEnabled(!busy && !watching);
        monitor.setText(watching ? "停止监测" : "开始监测"); monitor.setEnabled(!busy && hasAccount);
        test.setEnabled(!busy);
        long checked = LaundryStore.sp(this).getLong("checked", 0);
        String error = LaundryStore.sp(this).getString("error", "");
        boolean stale = checked == 0 || !error.isEmpty() || System.currentTimeMillis() - checked > Math.max(120000, LaundryStore.interval(this) * 2500L);
        String summary = watching ? LaundryMonitorService.running ? "正在后台监测" : "服务未运行，请停止后重新开始" : "监测已停止";
        if (busy) summary += "\n正在请求胖乖服务器…";
        if (checked > 0) summary += "\n上次成功查询：" + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(checked));
        if (!error.isEmpty()) summary += "\n" + error;
        status.setText(summary);
        String fingerprint = LaundryStore.sp(this).getString("devices", "[]") + LaundryStore.selected(this).toString() + stale + watching + busy;
        if (fingerprint.equals(rendered)) return;
        rendered = fingerprint; list.removeAllViews();
        List<LaundryDevice> devices = LaundryStore.devices(this);
        if (devices.isEmpty()) text(list, hasAccount ? "还没有最近使用的洗衣机，点击上方按钮重新读取。" : "登录后读取你最近用过的洗衣机。");
        Set<String> selected = LaundryStore.selected(this);
        for (LaundryDevice d : devices) {
            CheckBox box = new CheckBox(this);
            box.setText(d.name + "\n" + (stale ? "上次状态：" + d.label() + "（待核实）" : d.label()) + "\n" + d.org);
            box.setTextSize(16); box.setPadding(0, dp(8), 0, dp(8));
            box.setChecked(selected.contains(d.id)); box.setEnabled(!watching && !busy);
            box.setOnCheckedChangeListener((b, on) -> {
                Set<String> ids = LaundryStore.selected(this); if (on) ids.add(d.id); else ids.remove(d.id);
                LaundryStore.sp(this).edit().putStringSet("selected", ids).apply();
            });
            list.addView(box);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // 使用者重新打开页面时，可以从仍为开启的偏好恢复被中断的服务。
        if (LaundryStore.enabled(this) && !LaundryMonitorService.running && LaundryStore.loggedIn(this)
                && !LaundryStore.selected(this).isEmpty() && LaundryMonitorService.notificationsAllowed(this)) {
            try { LaundryMonitorService.start(this); }
            catch (RuntimeException e) { toast("监测尚未恢复，请重新点击开始"); }
        }
        ui.removeCallbacks(tick); tick.run();
    }
    @Override protected void onPause() { ui.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; ui.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
}
