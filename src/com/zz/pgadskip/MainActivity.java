package com.zz.pgadskip;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    TextView status;
    TextView restrictedStep, accessibilityStep, vpnStep, batteryStep;
    Button guideTitle;
    LinearLayout guide;
    CheckBox vpn;
    boolean refreshing = false, guideComplete = false, guideExpanded = false;
    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable refresh = new Runnable() {
        @Override public void run() { refreshStatus(); h.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.init(this);
        Blocklist.load(this);
        HideNames.load(this);
        if (b != null) {
            guideExpanded = b.getBoolean("guideExpanded");
            guideComplete = b.getBoolean("guideComplete");
        }
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        TextView title = new TextView(this);
        title.setText("胖乖去广告");
        title.setTextSize(24);
        root.addView(title);

        guideTitle = new Button(this);
        guideTitle.setOnClickListener(v -> {
            if (guideComplete) { guideExpanded = !guideExpanded; refreshStatus(); }
        });
        root.addView(guideTitle);
        guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.VERTICAL);
        root.addView(guide);
        restrictedStep = addStep("打开应用详情", this::openAppDetails);
        accessibilityStep = addStep("打开无障碍设置", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        vpnStep = addStep("开启网络拦截", () -> setVpn(true));
        batteryStep = addStep("打开应用详情", this::openAppDetails);

        TextView note = new TextView(this);
        note.setPadding(0, pad, 0, pad);
        note.setText("这是个人自用工具，与胖乖官方无关。只在胖乖生活里工作，不联网上传任何数据；VPN 只在本机处理胖乖的 DNS 查询，未拦截的查询照常转发给 DNS 服务器。\n\n"
                + "付款页面会自动关闭「领取3天胖乖会员」开关并点「放弃优惠」，取消勾选会跳转到微博/淘宝的推广；点「去支付」后被带进推广网页时自动返回。以上操作都可在下面的开关里关闭。\n\n"
                + "还会跳过开屏广告、关闭广告弹窗并遮挡推广区域；自己点进去领积分的激励视频不处理。");
        root.addView(note);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        CheckBox en = new CheckBox(this);
        en.setText("启用去广告");
        en.setChecked(Prefs.enabled(this));
        en.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "enabled", on));
        root.addView(en);

        vpn = new CheckBox(this);
        vpn.setText("网络拦截：胖乖的广告、直播加载不出来（本地 VPN，只管胖乖，状态栏会有钥匙图标）");
        vpn.setChecked(Prefs.vpn(this));
        vpn.setOnCheckedChangeListener((c, on) -> {
            if (!refreshing) setVpn(on);
        });
        root.addView(vpn);

        CheckBox cov = new CheckBox(this);
        cov.setText("抹掉网络拦不掉的部分：首页福利助手、直播流空框，付款后的推荐商品");
        cov.setChecked(Prefs.cover(this));
        cov.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "cover", on));
        root.addView(cov);

        CheckBox mall = new CheckBox(this);
        mall.setText("去掉底部的「商城」（有赞商城，网络上拦不了，改成盖住按钮）");
        mall.setChecked(Prefs.hideMall(this));
        mall.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "hideMall", on));
        root.addView(mall);

        CheckBox pts = new CheckBox(this);
        pts.setText("去掉底部的「积分」（积分余额在「我的」页还能看到）");
        pts.setChecked(Prefs.hidePoints(this));
        pts.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "hidePoints", on));
        root.addView(pts);

        CheckBox vip = new CheckBox(this);
        vip.setText("付款前不要胖乖会员：关掉默认打开的「领取3天胖乖会员」开关；跳出会员推销页时点「放弃优惠 原价使用」（那个会员之后 9.7 元/月自动续费）");
        vip.setChecked(Prefs.skipVipUpsell(this));
        vip.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "skipVipUpsell", on));
        root.addView(vip);

        CheckBox redir = new CheckBox(this);
        redir.setText("点「去支付」后被带去推广网页（如「微博校园」）时自动返回，再点一次去支付即可");
        redir.setChecked(Prefs.skipPayRedirect(this));
        redir.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "skipPayRedirect", on));
        root.addView(redir);

        CheckBox promo = new CheckBox(this);
        promo.setText("确认付款页自动取消勾选「阅读微博优惠」「闪购学生福利」这类推广（勾着时去支付会先跳去微博/淘宝）");
        promo.setChecked(Prefs.uncheckPromos(this));
        promo.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "uncheckPromos", on));
        root.addView(promo);

        CheckBox rec = new CheckBox(this);
        rec.setText("记录界面结构（仅调试用，会把胖乖页面文字记到本机日志，可能包含手机号、订单号和地址）");
        rec.setChecked(Prefs.record(this));
        rec.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "record", on));
        root.addView(rec);

        Button restore = new Button(this);
        restore.setText("恢复内置名单");
        restore.setOnClickListener(v -> {
            try {
                BuiltinLists.restore(this);
                Blocklist.load(this);
                HideNames.load(this);
                Toast.makeText(this, "已恢复两份内置名单", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } catch (Exception e) {
                Log.w("restore lists " + e);
                Toast.makeText(this, "恢复未完成，请重试（本机存储可能不可用）", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(restore);
        TextView listsNote = new TextView(this);
        listsNote.setText("恢复内置名单会覆盖 blocklist.txt 和 hide.txt 中的自定义修改。");
        root.addView(listsNote);
        root.setGravity(Gravity.TOP);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        b.putBoolean("guideExpanded", guideExpanded);
        b.putBoolean("guideComplete", guideComplete);
        super.onSaveInstanceState(b);
    }

    TextView addStep(String buttonText, Runnable action) {
        TextView label = new TextView(this);
        guide.addView(label);
        Button button = new Button(this);
        button.setText(buttonText);
        button.setOnClickListener(v -> action.run());
        guide.addView(button);
        return label;
    }

    void openAppDetails() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    void setVpn(boolean on) {
        Prefs.set(this, "vpn", on);
        if (!on) DnsVpnService.stop(this);
        else {
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, 1);   // 授权必须由使用者在系统弹窗确认。
            else DnsVpnService.start(this);
        }
        refreshStatus();
    }

    void refreshStatus() {
        boolean accessibility = serviceOn();
        boolean restricted = Build.VERSION.SDK_INT < 33 || accessibility;
        boolean network = Prefs.vpn(this) && DnsVpnService.running;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        restrictedStep.setText((restricted ? "✅" : "⬜") + " 1. 允许受限制的设置\n"
                + (Build.VERSION.SDK_INT < 33 ? "Android 12 及以下无需此步骤。" : "点右上角 ⋮ → 允许受限制的设置；无障碍开启后，此步显示完成。"));
        accessibilityStep.setText((accessibility ? "✅" : "⬜") + " 2. 开启无障碍服务「胖乖去广告」");
        vpnStep.setText((network ? "✅" : "⬜") + " 3. 开启网络拦截\n"
                + (network ? "本地 VPN 运行中。" : Prefs.vpn(this) ? "已开启，等待 VPN 启动；未授权时请再点下方按钮。" : "点击按钮，在系统 VPN 授权框中确认。"));
        batteryStep.setText((battery ? "✅" : "⬜") + " 4. 省电策略设为「无限制」\n省电策略 → 无限制；小米还要打开自启动。");
        boolean complete = restricted && accessibility && network && battery;
        if (complete && !guideComplete) guideExpanded = false;
        guideComplete = complete;
        guideTitle.setText(complete ? guideExpanded ? "✅ 设置完成（点此收起）" : "✅ 设置完成" : "首次使用：请完成以下设置");
        guide.setVisibility(!complete || guideExpanded ? View.VISIBLE : View.GONE);
        refreshing = true;
        vpn.setChecked(Prefs.vpn(this));
        refreshing = false;
        status.setText((accessibility ? "✅ 无障碍服务已开启" : "⬜ 无障碍服务未开启，请按上方步骤设置")
                + "\n已帮你跳过/关闭 " + Stats.hits(this) + " 次广告"
                + "\n网络拦截：" + (network ? "✅ 运行中（" + Blocklist.size() + " 条规则）"
                        : Prefs.vpn(this) ? "⏸ 已开启，等胖乖打开时启动（有别的 VPN 在跑时会让开）" : "未开启"));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1) {
            boolean allowed = res == RESULT_OK;
            Prefs.set(this, "vpn", allowed);
            if (allowed) DnsVpnService.start(this);
            refreshStatus();
        }
    }

    boolean serviceOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(s)) return false;
        ComponentName expected = new ComponentName(this, AdSkipService.class);
        for (String name : s.split(":")) if (expected.equals(ComponentName.unflattenFromString(name))) return true;
        return false;
    }
}
