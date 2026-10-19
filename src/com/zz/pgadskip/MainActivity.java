package com.zz.pgadskip;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        TextView title = new TextView(this);
        title.setText("胖乖去广告");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        Button open = new Button(this);
        open.setText("打开无障碍设置");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);

        CheckBox en = new CheckBox(this);
        en.setText("启用去广告");
        en.setChecked(Prefs.enabled(this));
        en.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "enabled", on));
        root.addView(en);

        CheckBox vpn = new CheckBox(this);
        vpn.setText("网络拦截：胖乖的广告、直播加载不出来（本地 VPN，只管胖乖，状态栏会有钥匙图标）");
        vpn.setChecked(Prefs.vpn(this));
        vpn.setOnCheckedChangeListener((c, on) -> {
            Prefs.set(this, "vpn", on);
            if (!on) { DnsVpnService.stop(this); return; }
            Intent consent = android.net.VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, 1);   // 系统弹窗，要用户自己点「确定」
            else DnsVpnService.start(this);
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

        CheckBox rec = new CheckBox(this);
        rec.setText("记录胖乖的界面结构（只存在手机本地，用来补规则）");
        rec.setChecked(Prefs.record(this));
        rec.setOnCheckedChangeListener((c, on) -> Prefs.set(this, "record", on));
        root.addView(rec);

        TextView note = new TextView(this);
        note.setPadding(0, pad, 0, 0);
        note.setText("只在胖乖生活里工作：点开屏广告的「跳过」、关掉插屏和广告弹窗。"
                + "激励视频（自己点进去领积分的）不碰；支付、订单、登录页面不点任何东西。\n\n"
                + "小米手机请在 设置 → 应用设置 → 胖乖去广告 里把省电策略设为「无限制」，否则可能被后台清掉。");
        root.addView(note);
        root.setGravity(Gravity.TOP);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText((serviceOn() ? "✅ 无障碍服务已开启" : "❌ 无障碍服务未开启，点下面按钮去打开「胖乖去广告」")
                + "\n已帮你跳过/关闭 " + Stats.hits(this) + " 次广告"
                + "\n网络拦截：" + (DnsVpnService.running ? "✅ 运行中（" + Blocklist.size() + " 条规则）"
                        : Prefs.vpn(this) ? "⏸ 已开启，等胖乖打开时启动（有别的 VPN 在跑时会让开）" : "未开启"));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1 && res == RESULT_OK) DnsVpnService.start(this);
        onResume();
    }

    boolean serviceOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(s)) return false;
        return s.contains(getPackageName() + "/");
    }
}
