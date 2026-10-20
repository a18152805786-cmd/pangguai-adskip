package com.zz.pgadskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 只对胖乖生活（com.qiekj.user）生效：
 * 1. 开屏广告：点「跳过」；
 * 2. 广告 SDK 的插屏/全屏视频页：点关闭，找不到就按返回；
 * 3. 首页上的广告弹窗：点弹窗里的关闭按钮。
 * 激励视频（用户自己点进去领积分的）不碰。
 */
public class AdSkipService extends AccessibilityService {

    static final String TARGET = "com.qiekj.user";

    /** 广告 SDK 的 Activity 前缀，来自胖乖生活 1.143.1 的清单。 */
    static final String[] AD_ACTIVITY_PREFIXES = {
            "com.bytedance.sdk.openadsdk.", "com.bytedance.msdk.", "com.qq.e.ads.",
            "com.kwad.sdk.", "com.baidu.mobads.", "com.sigmob.", "com.anythink.",
            "com.smartdigimkt.", "com.yd.saas.", "cj.mobile.ad.", "cj.mobile.fw.",
            "cj.mobile.wm.", "cn.vlion.ad.", "com.fl.saas.", "com.ja.adx.", "com.junion.",
            "com.tianmu.", "com.meishu.sdk.", "com.octopus.ad.", "com.ubix.", "com.wangmai.",
            "com.yfanads.", "com.jutui.ads.", "com.leyou.fusionsdk.", "com.mcto.sspsdk.",
            "com.miui.zeus.", "com.qy.sdk.", "com.huawei.openalliance.ad.", "com.jd.ad.sdk.",
            "com.qiekeji.pgad.", "com.qiekj.user.ui.activity.scan.adx.",
    };

    /** 广告落地页（点进广告后打开的网页/下载页），不自动关，免得误伤用户主动点开的内容。 */
    static final Pattern LANDING_PAGE = Pattern.compile(
            "(?i).*(WebView|WebPage|Web|H5|Browser|Download|Detail|Permissions|Feedback|DeepLink|Dplink|Gdpr).*Activity.*|.*WebViewAct$");

    /** 激励视频：用户自己选择看的，不碰。 */
    static final Pattern REWARD = Pattern.compile("(?i).*Reward.*");

    /** 胖乖自己的开屏页（现在叫 SplashActivity，改名了也能认）。 */
    static boolean isOwnSplash(String cls) {
        return cls.startsWith(TARGET + ".") && (cls.contains("Splash") || cls.contains("Launch") || cls.contains("Welcome"));
    }

    /** 「跳过」类文字：跳过、跳过广告、跳过 5、5s 跳过、5 | 跳过。 */
    static final Pattern SKIP_TEXT = Pattern.compile("^\\s*(\\d{1,2}\\s*[sS秒]?\\s*[|｜丨]?\\s*)?跳过(广告)?\\s*[|｜丨]?\\s*(\\d{1,2}\\s*[sS秒]?)?\\s*$");

    /** 关闭类文字/描述。 */
    static final Pattern CLOSE_TEXT = Pattern.compile("^\\s*(关闭|关闭广告|×|✕|✖|X|x|close|Close|CLOSE)\\s*$");

    /** 关闭/跳过按钮常见的 view id 片段。 */
    static final Pattern CLOSE_ID = Pattern.compile(
            "(?i).*(skip|close|dismiss|tt_splash_skip|ksad_skip|ksad_auto_close|ksad_end_close|gdt_close|iv_close|btn_close|img_close|close_btn|ad_close|tt_close|count_down).*");

    /** 广告标记：页面里出现这些字，才认为当前弹窗是广告。 */
    static final Pattern AD_MARK = Pattern.compile("^\\s*(广告|AD|Ad|ad|广告·|摇一摇.*|扭一扭.*|点击跳转.*|立即下载|查看详情|了解更多|打开看看)\\s*$");

    final Handler handler = new Handler(Looper.getMainLooper());
    // 进程级状态：系统重连无障碍服务时会新建服务对象，放在实例里就会忘了当前在哪一页（10-20 付款页挡板因此失效）
    static volatile String currentActivity = "";
    static volatile long activitySince = 0;
    int backTries = 0;
    long lastAction = 0;
    boolean scanPending = false;

    @Override
    protected void onServiceConnected() {
        Log.init(this);
        loadPages();
        Log.w("service connected, pages=" + pages.size());
        ensureVpn();
        scheduleScan(500);   // 连上时胖乖可能停在一个静止的弹窗上，不会再有事件来，主动看一次
    }

    /** 胖乖清单里登记的全部 Activity。在手机上实时读，胖乖更新后自动跟着变。 */
    java.util.Set<String> pages = new java.util.HashSet<>();
    long pagesLoadedAt = 0;

    void loadPages() {
        pagesLoadedAt = SystemClock.uptimeMillis();
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET, android.content.pm.PackageManager.GET_ACTIVITIES);
            java.util.Set<String> s = new java.util.HashSet<>();
            if (pi.activities != null) for (android.content.pm.ActivityInfo a : pi.activities) s.add(a.name);
            pages = s;
        } catch (Throwable t) { Log.w("loadPages " + t); }
    }

    /** 是不是一个真正的页面（Activity）；弹窗、浮层、PopupWindow 都不是。 */
    boolean isPage(String cls) {
        if (pages.contains(cls)) return true;
        // 不在清单里：可能胖乖刚更新，30 秒内最多重读一次
        if (SystemClock.uptimeMillis() - pagesLoadedAt > 30000 && cls.contains(".")) {
            loadPages();
            if (pages.contains(cls)) return true;
        }
        return pages.isEmpty() && cls.endsWith("Activity");
    }

    FeedCover cover;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (cover == null) cover = new FeedCover(this);
        boolean ours = e.getPackageName() != null && TARGET.contentEquals(e.getPackageName());
        if (!ours) {
            // 离开胖乖时要把挡板收起来；别的 app 的内容一概不看
            int t = e.getEventType();
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) cover.updateLater(0);
            return;
        }
        if (!Prefs.enabled(this)) { cover.hide(); return; }
        ensureVpn();
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) { cover.updateLater(0); return; }
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // 记下「去支付」被点的时间：之后几秒内如果被带去推广网页（微博校园等），自动返回
            AccessibilityNodeInfo clicked = e.getSource();
            boolean payTap = false;
            for (CharSequence t : e.getText()) if (t != null && "去支付".contentEquals(t)) payTap = true;
            if (!payTap && clicked != null) payTap = "tvToPay".equals(idOf(clicked))
                    || find(clicked, n -> n.getText() != null && "去支付".contentEquals(n.getText())) != null;
            if (payTap) { lastPayTapAt = SystemClock.uptimeMillis(); payTapPage = currentActivity; }
            boolean homeTab = false;
            for (CharSequence t : e.getText()) if (t != null && "首页".contentEquals(t)) homeTab = true;
            AccessibilityNodeInfo src = e.getSource();
            if (!homeTab && src != null) homeTab = find(src, n -> n.getText() != null && "首页".contentEquals(n.getText())) != null;
            if (homeTab) homeEnteredAt = SystemClock.uptimeMillis();
            // 点了底部导航栏：页面马上要换，先把挡板全收起来，免得旧位置的挡板盖住新页面的按钮
            if (clicked != null) {
                Rect cb = new Rect();
                clicked.getBoundsInScreen(cb);
                if (cb.top > screenRect().height() * 0.85) handler.post(() -> cover.hideAll());
            }
            // 点了底部标签等：挡板马上重新判断，0.3 秒后再核对一次（切页有动画）
            cover.updateLater(0);
            handler.postDelayed(() -> cover.updateLater(0), 300);
            return;
        }

        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getClassName() != null) {
            String cls = e.getClassName().toString();
            // 只把胖乖清单里的 Activity 当作页面切换；弹窗（如 xpopup 的 FullScreenDialog）不算
            if (isPage(cls)) {
                if (!cls.equals(currentActivity)) {
                    handler.post(() -> cover.hideAll());   // 换页面：先收起旧挡板
                    currentActivity = cls;
                    activitySince = SystemClock.uptimeMillis();
                    if (activitySince - lastPayTapAt < 5000 && PAY_REDIRECT_PAGE.matcher(cls).matches()) {
                        long since = activitySince;
                        handler.postDelayed(() -> checkPayRedirect(cls, since), 1200);
                    }
                    if (isHome(cls)) homeEnteredAt = activitySince;
                    backTries = 0;
                    Log.w("activity " + cls);
                }
            } else {
                Log.w("window " + cls + " over " + currentActivity);
            }
            if (Prefs.record(this)) Log.dumpLater(this, "state:" + cls);
        }
        scheduleScan(e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ? 50 : 250);
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 弹窗刚出来时内容还没画好，之后又静止不动、不再发事件（10-20 会员挽留框就这样卡住没被关），
            // 所以弹出后再补看三次
            for (long d : new long[]{400, 1000, 2000}) handler.postDelayed(() -> scheduleScan(0), d);
        }
        cover.updateLater(e.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ? 120 : 0);
    }

    long vpnCheckedAt = 0;
    long lastPayTapAt = -100000;
    String payTapPage = "";

    /** 点「去支付」后可能被带去的胖乖内置网页（微博校园这类推广就开在这里）。 */
    static final Pattern PAY_REDIRECT_PAGE = Pattern.compile("^com\\.qiekj\\.user\\.ui\\.activity\\.web\\.\\w+$");
    /** 网页标题带这些字的是真付款页，绝不返回。 */
    static final Pattern REAL_PAY_TITLE = Pattern.compile(".*(支付|收银|银联|付款|充值|订单|账单|钱包|银行|验证|密码).*");

    /**
     * 点了「去支付」却被带进推广网页（10-20 实测：默认勾着「阅读微博优惠0.5元」，去支付直接跳到「微博校园」）：
     * 看一眼网页标题，不是付款页就按返回。返回后那条推广会消失，再点一次去支付就正常付款。
     */
    void checkPayRedirect(String cls, long since) {
        if (!Prefs.skipPayRedirect(this) || !cls.equals(currentActivity) || activitySince != since) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !TARGET.contentEquals(root.getPackageName())) return;
        int sh = screenRect().height();
        StringBuilder title = new StringBuilder();
        for (AccessibilityNodeInfo n : findAll(root, x -> {
            if (!x.isVisibleToUser() || x.getText() == null || x.getText().length() == 0) return false;
            Rect b = new Rect();
            x.getBoundsInScreen(b);
            return b.top < sh * 0.12;
        }, 6)) title.append(n.getText()).append(' ');
        String t = title.toString().trim();
        if (t.isEmpty() || REAL_PAY_TITLE.matcher(t).matches()) {
            Log.w("pay redirect kept: " + cls + " title=" + t);
            return;
        }
        Log.w("BACK from pay redirect " + cls + " title=" + t + " (tapped in " + payTapPage + ")");
        Stats.hit(this);
        lastPayTapAt = -100000;
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.post(() -> android.widget.Toast.makeText(this, "已跳过「" + t + "」推广，请再点一次「去支付」", android.widget.Toast.LENGTH_LONG).show());
    }
    long gridFlippedAt = 0;
    /** 最近一次回到首页的时间：进入 MainActivity、推断出首页、或点了底部「首页」。 */
    static volatile long homeEnteredAt = 0;

    /** 胖乖一有动静就确认网络拦截开着；被 Clash 顶掉后、Clash 关了再打开胖乖时自动补上。 */
    void ensureVpn() {
        long now = SystemClock.uptimeMillis();
        if (now - vpnCheckedAt < 10000) return;
        vpnCheckedAt = now;
        if (!Prefs.vpn(this) || DnsVpnService.running) return;
        if (android.net.VpnService.prepare(this) != null) { Log.w("vpn needs consent again"); return; }
        if (DnsVpnService.otherVpnActive(this)) { Log.w("vpn skipped: another VPN is active"); return; }
        DnsVpnService.start(this);
    }

    void scheduleScan(long delay) {
        if (scanPending) return;
        scanPending = true;
        // 跟挡板同一个后台线程：主线程不被跨进程查询卡住，两边也不会同时查
        bg.postDelayed(() -> {
            scanPending = false;
            try { scan(); } catch (Throwable t) { Log.w("scan error " + t); }
        }, delay);
    }

    final Handler bg = new Handler(FeedCover.scanWorker.getLooper());

    // ── 确认付款页：取消勾选「会跳去别的 app」的推广（阅读微博优惠、闪购学生福利…）──
    // 这类推广默认勾着；勾着时点「去支付」不会付款，而是先拉起微博/淘宝，拒绝后还勾着，于是反复跳。
    // 勾选状态无障碍读不到（勾着的银联支付读出来也是未勾选），所以截屏看那个圆圈是不是蓝色。
    long promoShotAt = 0;
    boolean promoShotPending = false;
    final java.util.HashMap<String, Integer> promoTries = new java.util.HashMap<>();

    void uncheckTaskPromos(AccessibilityNodeInfo root) {
        if (android.os.Build.VERSION.SDK_INT < 30 || promoShotPending) return;
        long now = SystemClock.uptimeMillis();
        if (now - promoShotAt < 1500 || now - activitySince < 800) return;   // 页面刚出来时还在加载
        AccessibilityNodeInfo list = find(root, n -> n.isVisibleToUser() && "rvDiscount".equals(idOf(n)));
        if (list == null) return;
        List<String> names = new ArrayList<>();
        List<Rect> boxes = new ArrayList<>();
        for (AccessibilityNodeInfo sel : findAll(list, n -> n.isVisibleToUser() && "viewSelector".equals(idOf(n)), 6)) {
            if (!fresh(sel)) continue;
            AccessibilityNodeInfo row = sel.getParent();
            AccessibilityNodeInfo nameNode = row == null ? null : find(row, n -> "tvPromotionName".equals(idOf(n)));
            String name = nameNode == null || nameNode.getText() == null ? "" : nameNode.getText().toString();
            if (name.isEmpty() || name.contains("优惠券")) continue;   // 胖乖自己的优惠券是正常抵扣，不动
            if (promoTries.getOrDefault(activitySince + name, 0) >= 2) continue;
            Rect b = new Rect();
            sel.getBoundsInScreen(b);
            names.add(name);
            boxes.add(b);
        }
        if (names.isEmpty()) return;
        promoShotAt = now;
        promoShotPending = true;
        final long page = activitySince;
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                promoShotPending = false;
                try {
                    android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                    android.graphics.Bitmap bmp = hw == null ? null : hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                    r.getHardwareBuffer().close();
                    if (bmp == null || page != activitySince) return;
                    for (int i = 0; i < names.size(); i++) {
                        Rect b = boxes.get(i);
                        double blue = blueShare(bmp, b);
                        if (blue < 0.25) continue;
                        String key = page + names.get(i);
                        promoTries.put(key, promoTries.getOrDefault(key, 0) + 1);
                        Log.w("UNCHECK promo \"" + names.get(i) + "\" blue=" + String.format("%.2f", blue) + " " + b.toShortString());
                        Stats.hit(AdSkipService.this);
                        tap(b.centerX(), b.centerY());
                        return;   // 一次只点一个，下一轮再看
                    }
                } catch (Throwable t) { Log.w("promo shot error " + t); }
            }
            @Override public void onFailure(int code) { promoShotPending = false; Log.w("promo shot failed " + code); }
        });
    }

    /** 圆圈中间 60% 的区域里，勾选蓝（#3478F6 一类）占多少。 */
    static double blueShare(android.graphics.Bitmap bmp, Rect b) {
        int x0 = b.left + b.width() / 5, x1 = b.right - b.width() / 5, y0 = b.top + b.height() / 5, y1 = b.bottom - b.height() / 5;
        int total = 0, blue = 0;
        for (int x = Math.max(0, x0); x < Math.min(bmp.getWidth(), x1); x += 2)
            for (int y = Math.max(0, y0); y < Math.min(bmp.getHeight(), y1); y += 2) {
                int c = bmp.getPixel(x, y);
                int rr = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, bb = c & 0xFF;
                total++;
                if (bb > 200 && rr < 120 && gg > 80 && gg < 200) blue++;
            }
        return total == 0 ? 0 : (double) blue / total;
    }

    /** 支付、登录、人脸核验等第三方组件：永远不当广告。 */
    static final String[] NEVER_AD_PREFIXES = {
            "com.alipay.", "com.tencent.", "com.qiekj.user.wxapi.", "cmbapi.", "com.cmbchina.",
            "com.unionpay.", "com.dtf.face.", "com.huawei.hms.", "com.kwad.auth.", "com.baidu.oauth.",
            "com.journeyapps.", "com.yalantis.ucrop.", "com.luck.picture.", "com.lqr.imagepicker.",
            "cn.wildfire.", "com.azhon.appupdate.", "com.blankj.", "androidx.", "android.",
            // 胖乖自己的弹窗库（添加洗衣液/消毒液那个弹窗就是它）
            "com.lxj.xpopup.",
            // 抖音直播间：用户自己点进去的，不当广告关
            "com.byted.live.", "com.bytedance.android.openliveplugin.",
    };

    /** 名单之外的新广告 SDK：按类名特征识别，这样胖乖以后换了广告商也能认出来。 */
    static final Pattern AD_NAME = Pattern.compile(
            ".*(\\.ads?\\.|\\.adx\\.|\\.ad\\.|AD[A-Z]|Ad[A-Z]|Ad$|AD$|Ads[A-Z]|Splash|Interstitial|Reward|FullScreen|Spread|Ssp|ssp).*");

    static boolean isAdActivity(String cls) {
        for (String p : NEVER_AD_PREFIXES) if (cls.startsWith(p)) return false;
        for (String p : AD_ACTIVITY_PREFIXES) if (cls.startsWith(p)) return true;
        // 胖乖自己的页面不按名字猜（它的 SplashActivity 单独处理）
        if (cls.startsWith(TARGET + ".") || cls.startsWith("com.qiekeji.")) return false;
        return cls.contains(".") && AD_NAME.matcher(cls).matches();
    }

    /**
     * 服务刚启动（装新版、被系统重启）时胖乖可能已经开着，这时收不到页面切换事件，
     * 不知道当前是哪页，所有规则都不工作。按页面结构推断：有底部导航栏的就是首页。
     */
    void guessActivityIfUnknown() {
        if (!currentActivity.isEmpty()) return;
        // 最上层可能是弹窗，所以胖乖的每个窗口都看一遍
        String guess = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null || !TARGET.contentEquals(r.getPackageName())) continue;
            if (find(r, n -> "rv_navigation".equals(idOf(n))) != null) guess = TARGET + ".MainActivity";
            else if (find(r, n -> "tvToPay".equals(idOf(n)) || "rvDiscount".equals(idOf(n))) != null) guess = TARGET + ".ui.activity.scan.OrderPayPreviewAct";
            else if (find(r, n -> "tvSubmit".equals(idOf(n)) && n.getText() != null && "选好了".contentEquals(n.getText())) != null) guess = TARGET + ".ui.activity.scan.wash.WashSkuAct";
            if (guess != null) break;
        }
        if (guess == null) return;
        currentActivity = guess;
        activitySince = SystemClock.uptimeMillis();
        if (isHome(guess)) homeEnteredAt = activitySince;
        Log.w("activity guessed " + currentActivity);
    }

    /**
     * 确认付款页里默认打开的「领取3天胖乖会员 送1元红包」开关（switchNewVip）：
     * 那个会员 3 天后 9.7 元/月自动续费。开关状态读得到，只在开着时关，不会把关着的误打开。
     */
    final java.util.HashMap<String, Integer> vipSwitchTries = new java.util.HashMap<>();

    boolean turnOffVipSwitch(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo sw = find(root, n -> {
            if (!n.isVisibleToUser() || !n.isCheckable() || !n.isChecked()) return false;
            CharSequence c = n.getClassName();
            if (c == null || !c.toString().contains("Switch")) return false;
            AccessibilityNodeInfo row = n.getParent();
            AccessibilityNodeInfo label = row == null ? null : find(row, x -> x.getText() != null && x.getText().toString().contains("会员"));
            return "switchNewVip".equals(idOf(n)) || label != null;
        });
        if (sw == null || !fresh(sw) || !sw.isChecked()) return false;
        String key = activitySince + "";
        int tries = vipSwitchTries.getOrDefault(key, 0);
        if (tries >= 2) return false;
        vipSwitchTries.put(key, tries + 1);
        Rect b = new Rect();
        sw.getBoundsInScreen(b);
        lastAction = SystemClock.uptimeMillis();
        Log.w("TURN OFF vip switch " + idOf(sw) + " " + b.toShortString() + " try " + (tries + 1));
        Stats.hit(this);
        if (!(sw.isClickable() && sw.performAction(AccessibilityNodeInfo.ACTION_CLICK))) tap(b.centerX(), b.centerY());
        return true;
    }

    void scan() {
        guessActivityIfUnknown();
        if (SystemClock.uptimeMillis() - lastAction < 600) return;
        String act = currentActivity;
        boolean adAct = isAdActivity(act);
        if (adAct && (REWARD.matcher(act).matches() || LANDING_PAGE.matcher(act).matches())) return;

        List<AccessibilityWindowInfo> wins = getWindows();
        for (AccessibilityWindowInfo w : wins) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !TARGET.contentEquals(root.getPackageName())) continue;

            Rect wb = new Rect();
            w.getBoundsInScreen(wb);
            Rect screen = screenRect();
            boolean fullScreen = wb.width() >= screen.width() * 0.95 && wb.height() >= screen.height() * 0.85;

            // 1. 「跳过」：开屏页、广告页、或任何窗口里出现的跳过按钮都点
            AccessibilityNodeInfo skip = find(root, n -> matchText(n, SKIP_TEXT) || (idOf(n).toLowerCase().contains("skip") && n.isVisibleToUser()));
            if (skip != null) { act("skip", skip); return; }

            // 1b. 会员挽留弹窗「你有3天免费会员待领取 本单还可立减1元 [放弃优惠][立即领取]」：点「放弃优惠」。
            //     按内容认，不看当前页面和窗口层级（全屏弹窗挡住时系统只报弹窗这一个窗口）
            if (Prefs.skipVipUpsell(this) && find(root, n -> "tvToPay".equals(idOf(n))) == null
                    && find(root, n -> n.isVisibleToUser() && n.getText() != null && n.getText().toString().contains("会员")) != null) {
                AccessibilityNodeInfo giveUp = find(root, n -> {
                    CharSequence t = n.getText();
                    return n.isVisibleToUser() && t != null && t.toString().trim().equals("放弃优惠");
                });
                if (giveUp != null && fresh(giveUp)) { act("decline-vip-retain-popup", giveUp); return; }
            }

            // 2. 广告 SDK 的全屏页 / 开屏页：找关闭按钮，找不到等一会儿按返回
            if (adAct || isOwnSplash(act)) {
                AccessibilityNodeInfo close = find(root, n -> matchText(n, CLOSE_TEXT) || closeIdSmall(n, screen));
                if (close != null) { act("close-in-ad-activity", close); return; }
                // 按返回只用在第三方广告 SDK 的页面；胖乖自己的扫码广告页按返回可能把扫码流程一起退掉
                if (adAct && fullScreen && !act.startsWith(TARGET + ".") && !act.startsWith("com.qiekeji.")) {
                    long stay = SystemClock.uptimeMillis() - activitySince;
                    if (stay > 3000 && backTries < 3 && stay > 3000L + backTries * 2500L) {
                        backTries++;
                        lastAction = SystemClock.uptimeMillis();
                        Log.w("BACK in " + act + " try " + backTries);
                        Stats.hit(this);
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        return;
                    }
                }
                continue;
            }

            // 选套餐、确认付款、登录这类页面：下面两条一律不碰
            if (SAFE_PAGE.matcher(act).matches()) {
                if (act.endsWith(".OrderPayPreviewAct")) {
                    if (Prefs.skipVipUpsell(this) && turnOffVipSwitch(root)) return;
                    if (Prefs.uncheckPromos(this)) uncheckTaskPromos(root);
                }
                // 付款相关页面只做两件事，别的按钮一概不碰：
                // a) 会员推销页（「优惠支付：领取3天胖乖会员」，之后 9.7 元/月自动续费）：点「放弃优惠 原价使用」
                if (Prefs.skipVipUpsell(this) && VIP_UPSELL_PAGE.matcher(act).matches()) {
                    AccessibilityNodeInfo decline = find(root, n -> {
                        CharSequence t = n.getText();
                        return n.isVisibleToUser() && t != null && t.toString().contains("放弃优惠");
                    });
                    if (decline != null && fresh(decline)) { act("decline-vip-upsell", decline); return; }
                }
                // b) 控件名本身就是「广告的关闭键」（ad_close、ivAdClose 这类）：点它。选套餐页顶部的美团广告就是这种
                AccessibilityNodeInfo adClose = find(root, n -> {
                    if (!n.isVisibleToUser()) return false;
                    String id = idOf(n);
                    return AD_ID.matcher(id).matches() && id.toLowerCase().contains("close") && closeIdSmall(n, screen);
                });
                if (adClose != null) { act("close-ad-on-pay-page", adClose); return; }
                continue;
            }

            // 3. 带广告标记的弹窗/浮层（付款结果页上的广告也算）：点关闭
            // 3a. 广告位（id 认得出来的）：只点它里面或紧挨着它的关闭键，不碰页面上别的按钮
            for (AccessibilityNodeInfo box : findAll(root, n -> n.isVisibleToUser() && AD_ID.matcher(idOf(n)).matches(), 12)) {
                AccessibilityNodeInfo close = closeNear(root, box, screen);
                if (close != null) { act("close-ad-box", close); return; }
            }
            // 3b. 标着「广告」的弹窗：整个弹窗里找关闭；全屏页面上的「广告」角标只在它附近找
            AccessibilityNodeInfo mark = find(root, n -> matchText(n, AD_MARK));
            if (mark != null) {
                AccessibilityNodeInfo close;
                if (!fullScreen) {
                    close = find(root, n -> matchText(n, CLOSE_TEXT) || closeIdSmall(n, screen));
                } else {
                    AccessibilityNodeInfo box = mark;
                    for (int i = 0; i < 3 && box.getParent() != null; i++) box = box.getParent();
                    close = closeNear(root, box, screen);
                }
                if (close != null) { act(fullScreen ? "close-ad-layer" : "close-ad-popup", close); return; }
            }

            // 6. 首页图标区翻到了一页「全是被抹掉的图标」（比如第三页只有 AI课程表、AI错题本）：
            //    整页看着是空的，用 ViewPager 自带的「向前翻」翻回去
            if (isHome(act) && !isPopupWindow(w, wins)) {
                AccessibilityNodeInfo grid = find(root, n -> n.isVisibleToUser() && "vp_main".equals(idOf(n)) && n.isScrollable());
                if (grid != null && fresh(grid)) {
                    java.util.Set<String> hide = HideNames.get(this);
                    List<AccessibilityNodeInfo> labels = findAll(grid, n -> {
                        if (!n.isVisibleToUser() || n.getText() == null || !"tv_name".equals(idOf(n)) || !fresh(n)) return false;
                        Rect b = new Rect();
                        n.getBoundsInScreen(b);
                        return b.left >= 0 && b.right <= screen.width();   // 只看完整显示在屏幕上的
                    }, 12);
                    int hiddenCount = 0;
                    for (AccessibilityNodeInfo l : labels) if (hide.contains(l.getText().toString().trim())) hiddenCount++;
                    boolean allHidden = !labels.isEmpty() && hiddenCount == labels.size();
                    // 刚回到首页 3 秒内，这一页大半是被抹掉的图标：也翻回去，让洗烘、饮水那一页露出来。
                    // 过了 3 秒就只在「整页全空」时翻，免得用户自己滑到「设备地图」又被弹回去。
                    boolean mostlyHidden = labels.size() > 0 && hiddenCount * 2 > labels.size();
                    long now = SystemClock.uptimeMillis();
                    boolean justEntered = now - homeEnteredAt < 3000;
                    if ((allHidden || (mostlyHidden && justEntered)) && now - gridFlippedAt > 600) {
                        gridFlippedAt = now;
                        Log.w("grid page " + hiddenCount + "/" + labels.size() + " hidden, scroll back");
                        grid.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                        return;
                    }
                }
            }

            // 5. 已隐藏的标签页（商城、积分）露出来了（比如左右滑动切进去）：点「首页」切回去
            if (isHome(act) && !isPopupWindow(w, wins)) {
                boolean hideMall = Prefs.hideMall(this), hidePoints = Prefs.hidePoints(this);
                AccessibilityNodeInfo hidden = find(root, n -> {
                    if (!n.isVisibleToUser()) return false;
                    CharSequence t = n.getText(), d = n.getContentDescription();
                    if (hideMall && ("youzanBrowserContainer".equals(idOf(n)) || (t != null && "有赞商城".contentEquals(t)))) return true;
                    // 积分页是个网页，顶上写「胖乖赚钱」，里面有「我的积分」（「我的」页里只写「积分」，不会误认）
                    return hidePoints && ((t != null && "我的积分".contentEquals(t)) || (d != null && "胖乖赚钱".contentEquals(d)));
                });
                if (hidden != null && fresh(hidden)) {
                    AccessibilityNodeInfo homeTab = find(root, n -> {
                        CharSequence t = n.getText();
                        if (!n.isVisibleToUser() || t == null || !"首页".contentEquals(t)) return false;
                        Rect b = new Rect();
                        n.getBoundsInScreen(b);
                        return b.top > screen.height() * 0.85;
                    });
                    if (homeTab != null) { act("leave-hidden-tab", homeTab); return; }
                }
            }

            // 4. 首页上的活动弹窗（胖乖自己的，不一定标「广告」）：首页没有扣钱的按钮，见到关闭就点
            if (isHome(act) || POPUP_PAGE.matcher(act).matches()) {
                boolean dialog = isPopupWindow(w, wins) || POPUP_PAGE.matcher(act).matches();
                // 只认 id 里明确写着 close 的键；cancel、「返回」一律不点（22:27 曾把直播间的返回键点掉）
                AccessibilityNodeInfo close = find(root, n -> !isBackLike(n)
                        && ((dialog && matchText(n, CLOSE_TEXT)) || (closeIdSmall(n, screen) && idOf(n).toLowerCase().contains("close"))));
                if (close != null) { act(dialog ? "close-home-dialog" : "close-home-layer", close); return; }
            }
        }
    }

    static boolean isBackLike(AccessibilityNodeInfo n) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        String s = (t == null ? "" : t.toString()) + "|" + (d == null ? "" : d.toString());
        return s.contains("返回") || s.contains("取消") || s.contains("退出");
    }

    static boolean isHome(String cls) {
        return cls.equals(TARGET + ".MainActivity") || cls.endsWith(".IndexAct") || cls.endsWith(".HomeActivity");
    }

    /** 胖乖自己做成 Activity 的弹窗页，如 IntegralPopupAct、CommunityTaskPopup。 */
    static final Pattern POPUP_PAGE = Pattern.compile("^com\\.qiekj\\.user\\..*(Popup|Dialog)\\w*$");

    /** 不是最底下那层页面窗口的胖乖窗口，就是弹窗。 */
    static boolean isPopupWindow(AccessibilityWindowInfo w, List<AccessibilityWindowInfo> wins) {
        int minLayer = Integer.MAX_VALUE;
        for (AccessibilityWindowInfo x : wins) {
            if (x.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = x.getRoot();
            if (r != null && r.getPackageName() != null && TARGET.contentEquals(r.getPackageName()))
                minLayer = Math.min(minLayer, x.getLayer());
        }
        return w.getLayer() > minLayer;
    }

    /** 跟钱和账号有关、点错会有后果的页面。付款结果/启动成功页不在里面，那里的广告可以关。 */
    /** 付款前插进来的会员推销页。after.PgVipOrderAct 是付完款的，不在这里。 */
    static final Pattern VIP_UPSELL_PAGE = Pattern.compile("^com\\.qiekj\\.user\\..*\\.before\\.PgVipOrder\\w*$");

    static final Pattern SAFE_PAGE = Pattern.compile(
            "(?i).*(\\.PayAct$|OrderPayPreview|NotPayOrder|SkuAct$|SkuNewAct$|SelCoupon|Login|AuthCode|Pwd|Password|BindPhone|BindExternal|AccountPhone|TicketBuy|VipBuy|VipOrder|PromotionPay|WeBankBuy|Refund|CustomScanCode|HomeScanCode|LockingAct).*");

    /** 广告 SDK 自带的 view id 前缀。 */
    static final Pattern AD_ID = Pattern.compile(
            "(?i)^(tt_|ksad_|gdt_|sig_|anythink|myoffer|ms_|tianmu|octopus|ubix|junion|wm_|yd_|beizi|bd_|mobads).*"
            // 胖乖自己的广告位：rl_float_ad、channel_banner_iv、iv_ad_close 这类，id 里有独立的 ad 段
            + "|.*(^|_)(ad|ads|adv|advert)(_|$).*|.*(float_?ad|channel_banner|splash_ad|ad_?banner).*"
            // 驼峰写法：flAdTopOn、ivAdClose（Ad 后面跟大写，排除 tvAddress 这类）
            + "|(?-i:.*[a-z]Ad[A-Z0-9].*|^ad[A-Z].*)");

    boolean closeIdSmall(AccessibilityNodeInfo n, Rect screen) {
        if (!n.isVisibleToUser()) return false;
        String id = idOf(n);
        if (id.isEmpty() || !CLOSE_ID.matcher(id).matches()) return false;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        // 关闭按钮都很小；防止把整块区域当成按钮
        return b.width() > 0 && b.width() < screen.width() / 3 && b.height() < screen.height() / 8;
    }

    /** 同一个按钮 30 秒内最多点 2 次：点了不消失的，说明不是广告的关闭键，别一直点。 */
    final java.util.HashMap<String, long[]> clicked = new java.util.HashMap<>();

    void act(String why, AccessibilityNodeInfo n) {
        if (!fresh(n)) return;
        if (isBackLike(n)) { Log.w("skip back-like node " + idOf(n) + " in " + currentActivity); return; }
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        String key = currentActivity + "|" + idOf(n) + "|" + b.toShortString();
        long now = SystemClock.uptimeMillis();
        long[] rec = clicked.get(key);
        if (rec != null && now - rec[0] < 30000) {
            if (rec[1] >= 2) return;
            rec[1]++;
        } else {
            clicked.put(key, new long[]{now, 1});
        }
        lastAction = now;
        Log.w("CLICK " + why + " in " + currentActivity + " id=" + idOf(n) + " text=" + n.getText()
                + " desc=" + n.getContentDescription() + " " + b.toShortString());
        Stats.hit(this);
        AccessibilityNodeInfo p = n;
        for (int i = 0; i < 4 && p != null; i++) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            p = p.getParent();
        }
        tap(b.centerX(), b.centerY());
    }

    void tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build();
        dispatchGesture(g, null, null);
    }

    Rect screenRect() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    /**
     * 向胖乖重新查一次这个控件，返回它现在是否真的可见。
     * 无障碍服务的节点有缓存：胖乖切底部标签时不发通知，缓存里首页的控件会一直"可见、在屏幕内"，
     * 10-19 因此在「我的」页上按首页的位置画了挡板，把订单按钮盖住了。画挡板、点按钮之前都先过这一关。
     */
    static boolean fresh(AccessibilityNodeInfo n) {
        try { return n != null && n.refresh() && n.isVisibleToUser(); } catch (Throwable t) { return false; }
    }

    static String idOf(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        if (id == null) return "";
        int i = id.indexOf(":id/");
        return i >= 0 ? id.substring(i + 4) : id;
    }

    static boolean matchText(AccessibilityNodeInfo n, Pattern p) {
        if (!n.isVisibleToUser()) return false;
        CharSequence t = n.getText(), d = n.getContentDescription();
        return (t != null && t.length() < 16 && p.matcher(t).matches())
                || (d != null && d.length() < 16 && p.matcher(d).matches());
    }

    /** 广告容器里面，或者四周 80px 以内的关闭键（关闭键常常压在广告图的角上、不在同一个父控件里）。 */
    AccessibilityNodeInfo closeNear(AccessibilityNodeInfo root, AccessibilityNodeInfo box, Rect screen) {
        AccessibilityNodeInfo inside = find(box, n -> matchText(n, CLOSE_TEXT) || closeIdSmall(n, screen));
        if (inside != null) return inside;
        Rect b = new Rect();
        box.getBoundsInScreen(b);
        if (b.width() > screen.width() * 0.9 && b.height() > screen.height() * 0.5) return null; // 太大，等于整页
        b.inset(-80, -80);
        return find(root, n -> {
            if (!(matchText(n, CLOSE_TEXT) || closeIdSmall(n, screen))) return false;
            Rect c = new Rect();
            n.getBoundsInScreen(c);
            return Rect.intersects(b, c);
        });
    }

    static List<AccessibilityNodeInfo> findAll(AccessibilityNodeInfo root, Pred pred, int max) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        int seen = 0;
        while (!stack.isEmpty() && seen < 3000 && out.size() < max) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            seen++;
            if (pred.test(n)) out.add(n);
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
        }
        return out;
    }

    interface Pred { boolean test(AccessibilityNodeInfo n); }

    static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Pred pred) {
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        int seen = 0;
        while (!stack.isEmpty() && seen < 3000) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            seen++;
            if (pred.test(n)) return n;
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
        }
        return null;
    }

    @Override
    public void onInterrupt() { }
}
