package com.zz.pgadskip;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 去不掉的嵌入式广告，就在上面盖挡板：看不到、也点不进去。
 * 首页：直播带货流。付款后/启动成功页：推荐商品卡、底部直播流、没有关闭键的广告位。
 * 点一下任意挡板，全部让开 10 秒；有弹窗、离开这些页面、离开胖乖时自动收起。
 */
final class FeedCover {
    final AdSkipService s;
    final WindowManager wm;
    final Handler h = new Handler(Looper.getMainLooper());
    final List<FrameLayout> views = new ArrayList<>();
    final List<Rect> shownRects = new ArrayList<>();
    boolean pending = false;
    boolean ticking = false;
    long suppressUntil = 0;

    /** 付款后、启动成功、洗衣中（订单详情）这类页面。按名字认，胖乖改了具体类名也大概率还能对上。 */
    static final Pattern AFTER_PAY = Pattern.compile(
            "^com\\.qiekj\\.user\\..*(Startup|StartStatus|PayResult|PaySuccess|PayState|AfterPayUse|OpenSuccess|ReserveSuccess|AppointmentResult|AppointResult|OrderDetail)\\w*$");

    static final Pattern RECOMMEND_ID = Pattern.compile("(?i).*(youzan_recommend|recommend_goods|goods_recommend).*");
    static final Pattern RECOMMEND_TITLE = Pattern.compile(".*(推荐好物|边洗边逛|边洗边买|猜你喜欢|为你推荐|好物推荐).*");

    static final class Block {
        final Rect r; final String label;
        /** true：只遮挡、不拦触摸（手指能透过去滑动页面）。直播流的空框用这个，免得页面滚上去后整页被盖住、滑不回来。 */
        boolean passThrough = false;
        Block(Rect r, String label) { this.r = r; this.label = label; }
        Block through() { passThrough = true; return this; }
        /** 不写字、跟页面同色：看起来就像那块被删掉了。 */
        boolean quiet() { return label.isEmpty(); }
    }

    /** 胖乖页面底色是 #F5F5F6，但系统会把悬浮层压暗约 4%（设 F5F5F7 实际显示 ECECEF），所以这里设得更亮来抵消。10-19 截图取色。 */
    static final int PAGE_BG = Color.parseColor("#FEFEFF");

    FeedCover(AdSkipService s) {
        this.s = s;
        this.wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 付款前的页面：选套餐、确认付款。这里只按 hide.txt 的 id: 抹东西（顺手买一单、会员横幅），别的规则不跑。 */
    static final Pattern PRE_PAY = Pattern.compile("^com\\.qiekj\\.user\\..*(SkuAct|SkuNewAct|OrderPayPreviewAct)$");

    static boolean isCoverPage(String act) {
        return AdSkipService.isHome(act) || AFTER_PAY.matcher(act).matches() || PRE_PAY.matcher(act).matches();
    }

    /**
     * 扫描放在后台线程：每次扫描要向胖乖查几百个控件（每个都是一次跨进程调用），
     * 10-20 放在主线程时，付款页上太慢，系统反复把无障碍服务断开重连。主线程只负责画挡板。
     */
    static final android.os.HandlerThread worker = new android.os.HandlerThread("pg-cover");
    /** 规则扫描单独一个线程：扫描要遍历整页，放一起时挡板更新排在后面干等（10-20 切标签挡板晚好几秒）。 */
    static final android.os.HandlerThread scanWorker = new android.os.HandlerThread("pg-scan");
    static { scanWorker.start(); }
    static { worker.start(); }
    final Handler bg = new Handler(worker.getLooper());

    void updateLater(long delay) {
        if (pending) return;
        pending = true;
        bg.postDelayed(() -> {
            pending = false;
            List<Block> blocks;
            try { blocks = compute(); } catch (Throwable t) { Log.w("cover error " + t); blocks = null; }
            final List<Block> result = blocks;
            h.post(() -> apply(result));
        }, delay);
    }

    /** 后台线程：算出要画哪些挡板；null = 全部收起并停掉定时核对。 */
    List<Block> compute() {
        String act = AdSkipService.currentActivity;
        if (!Prefs.enabled(s) || !Prefs.cover(s) || SystemClock.uptimeMillis() < suppressUntil || !isCoverPage(act)) return null;
        AccessibilityNodeInfo root = topRoot();
        // 胖乖不在前台或有弹窗：全部收起。弹窗关掉时不一定有事件（10-20 会员挽留框关后「顺手买一单」没再盖上），
        // 所以还在这些页面上时，半秒后再看一次
        if (root == null) { retryLater = true; return null; }
        return findBlocks(root, act);
    }

    volatile boolean retryLater = false;

    /** 主线程：按算好的结果画挡板。 */
    void apply(List<Block> blocks) {
        try {
            if (blocks == null) {
                hideAll();
                if (retryLater) { retryLater = false; h.postDelayed(() -> updateLater(0), 500); }
                return;
            }
            startTick();
            for (int i = 0; i < blocks.size(); i++) show(i, blocks.get(i));
            for (int i = blocks.size(); i < views.size(); i++) hideAt(i);
        } catch (Throwable t) { Log.w("cover apply error " + t); hideAll(); }
    }

    /** 最上层是胖乖、焦点在胖乖、上面没有弹窗时，返回它的根节点。 */
    AccessibilityNodeInfo topRoot() {
        List<AccessibilityWindowInfo> wins = s.getWindows();
        AccessibilityWindowInfo top = null;
        int ours = 0;
        for (AccessibilityWindowInfo w : wins) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (top == null || w.getLayer() > top.getLayer()) top = w;
            AccessibilityNodeInfo x = w.getRoot();
            if (x != null && x.getPackageName() != null && AdSkipService.TARGET.contentEquals(x.getPackageName())) ours++;
        }
        // 下拉通知栏、系统对话框时焦点不在胖乖上；胖乖有两个以上窗口说明有弹窗，挡板会压住弹窗按钮
        if (top == null || !(top.isActive() || top.isFocused()) || ours != 1) return null;
        AccessibilityNodeInfo root = top.getRoot();
        if (root == null || root.getPackageName() == null || !AdSkipService.TARGET.contentEquals(root.getPackageName())) return null;
        return root;
    }

    // ── 快速查找：让胖乖那边按 id / 文字直接搜，一次跨进程调用就返回，不再逐个遍历几千个控件 ──
    // （10-20：首页直播网页控件很多，逐个遍历一次要好几秒，切标签时挡板跟不上，还盖住了「我的」页的订单按钮）
    static List<AccessibilityNodeInfo> byId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(AdSkipService.TARGET + ":id/" + id))
                if (n != null && n.isVisibleToUser()) out.add(n);
        } catch (Throwable ignored) { }
        return out;
    }

    static AccessibilityNodeInfo firstById(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = byId(root, id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** exact=true 只要文字完全相同的；false 包含即可。 */
    static List<AccessibilityNodeInfo> byText(AccessibilityNodeInfo root, String text, boolean exact) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) {
                if (n == null || !n.isVisibleToUser() || n.getText() == null) continue;
                String t = n.getText().toString().trim();
                if (exact ? t.equals(text) : t.contains(text)) out.add(n);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    List<Block> findBlocks(AccessibilityNodeInfo root, String act) {
        List<Block> out = new ArrayList<>();
        int bottomLimit = bottomBarTop(root);

        // 1. 直播带货流：id 以 ll_scrolling_view 开头（首页是 ll_scrolling_view，启动页是 ll_scrolling_view_mall）
        List<AccessibilityNodeInfo> feeds = byId(root, "ll_scrolling_view");
        feeds.addAll(byId(root, "ll_scrolling_view_mall"));
        if (feeds.isEmpty()) {
            // 以后改了 id：从「进入直播间」「直播中」往上找最近一层能滚动的容器
            List<AccessibilityNodeInfo> marks = byText(root, "进入直播间", true);
            if (marks.isEmpty()) marks = byText(root, "直播中", true);
            AccessibilityNodeInfo mark = marks.isEmpty() ? null : marks.get(0);
            AccessibilityNodeInfo p = mark == null ? null : mark.getParent();
            for (int i = 0; i < 30 && p != null; i++) {
                if (p.isScrollable()) { feeds.add(p); break; }
                p = p.getParent();
            }
        }
        int topLimit = toolbarBottom(root);
        // 直播流已经被网络拦截挡住加载不出来，这里只把剩下的空框/占位图抹平
        for (AccessibilityNodeInfo f : feeds) {
            int before = out.size();
            addBlock(out, f, "", topLimit, bottomLimit, 150);
            if (out.size() > before) out.get(out.size() - 1).through();
        }

        if (AdSkipService.isHome(act)) {
            // 胖乖福利助手：数据跟正常功能走同一个接口，网络上拦不了，只能抹掉
            AccessibilityNodeInfo w = firstById(root, "welfare_assistant");
            if (w == null) {
                List<AccessibilityNodeInfo> ts = byText(root, "胖乖福利助手", true);
                AccessibilityNodeInfo t = ts.isEmpty() ? null : ts.get(0);
                for (int i = 0; i < 4 && t != null; i++) {
                    t = t.getParent();
                    if (t == null) break;
                    Rect b = new Rect();
                    t.getBoundsInScreen(b);
                    if (b.width() >= s.getResources().getDisplayMetrics().widthPixels * 0.8) { w = t; break; }
                }
            }
            if (w != null) addBlock(out, w, "", topLimit, bottomLimit, 80);

            // 底部导航栏的「商城」（有赞，网络上拦不了，拦了胖乖会崩）、「积分」：把按钮盖住、点不到
            if (Prefs.hideMall(s)) {
                Rect tab = navTab(root, "商城");
                if (tab != null) out.add(new Block(tab, ""));
            }
            if (Prefs.hidePoints(s)) {
                Rect tab = navTab(root, "积分");
                if (tab != null) out.add(new Block(tab, ""));
            }

            // hide.txt 里的名单（胖乖首页、「我的」页都是 MainActivity）：
            //   普通一行 = 图标名，抹掉那个图标格；「卡片:标题」= 抹掉标题所在的整张卡片；「id:控件名」= 按控件 id 抹掉
            int sw = s.getResources().getDisplayMetrics().widthPixels;
            int sh = s.getResources().getDisplayMetrics().heightPixels;
            java.util.Set<String> icons = new java.util.HashSet<>(), cards = new java.util.HashSet<>(), ids = new java.util.HashSet<>();
            for (String line : HideNames.get(s)) {
                if (line.startsWith("卡片:") || line.startsWith("卡片：")) cards.add(line.substring(3).trim());
                else if (line.startsWith("id:")) ids.add(line.substring(3).trim());
                else icons.add(line);
            }
            int headerBottom = Math.max(topLimit, headerBottom(root));
            if (!icons.isEmpty() || !cards.isEmpty()) {
                List<AccessibilityNodeInfo> labels = new ArrayList<>();
                for (String name : icons) labels.addAll(byText(root, name, true));
                for (String name : cards) labels.addAll(byText(root, name, true));
                for (AccessibilityNodeInfo label : labels) {
                    String t = label.getText().toString().trim();
                    Rect r = cards.contains(t) ? cardOf(label, sw, sh) : iconCell(label, sw);
                    if (r == null) continue;
                    if (r.left < 0) r.left = 0;                 // 左右滑动时格子会有一半在屏幕外
                    if (r.right > sw) r.right = sw;
                    if (r.top < headerBottom) r.top = headerBottom;
                    if (r.bottom > bottomLimit) r.bottom = bottomLimit;
                    if (r.width() > 30 && r.height() > 40) out.add(new Block(r, ""));
                }
            }
            coverIds(root, ids, out, headerBottom, bottomLimit);
        }

        if (PRE_PAY.matcher(act).matches()) {
            // 选套餐、确认付款页：只抹 hide.txt 里 id: 写明的块（顺手买一单、会员横幅），不碰「去支付」那一栏
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (String line : HideNames.get(s)) if (line.startsWith("id:")) ids.add(line.substring(3).trim());
            AccessibilityNodeInfo pay = firstById(root, "clBottom");
            if (pay == null) pay = firstById(root, "bottomView");
            int payTop = bottomLimit;
            if (pay != null) {
                Rect b = new Rect();
                pay.getBoundsInScreen(b);
                payTop = Math.min(payTop, b.top);
            }
            coverIds(root, ids, out, topLimit, payTop);
        }

        if (AFTER_PAY.matcher(act).matches()) {
            // 2. 推荐商品卡：id 认得出来就用 id，否则找「推荐好物」这类标题，取它所在的整张卡
            List<AccessibilityNodeInfo> recs = byId(root, "youzan_recommend");
            if (recs.isEmpty()) {
                AccessibilityNodeInfo title = null;
                for (String k : new String[]{"推荐好物", "边洗边逛", "边洗边买", "猜你喜欢", "为你推荐", "好物推荐"}) {
                    for (AccessibilityNodeInfo n : byText(root, k, false))
                        if (n.getText().length() < 20) { title = n; break; }
                    if (title != null) break;
                }
                AccessibilityNodeInfo card = title == null ? null : title.getParent();
                if (card != null) recs.add(card);
            }
            for (AccessibilityNodeInfo r : recs) addBlock(out, r, "", topLimit, bottomLimit, 80);

            // 3. 广告位（cl_ad 这类）：有关闭键的交给主服务去点；这里把还露着的盖上
            for (AccessibilityNodeInfo a : AdSkipService.findAll(root,
                    n -> n.isVisibleToUser() && AdSkipService.AD_ID.matcher(AdSkipService.idOf(n)).matches(), 6)) {
                addBlock(out, a, "", topLimit, bottomLimit, 80);
            }
        }
        return out;
    }

    /** 按控件 id 抹掉：上下不超过 top/bottom，太高（超过屏幕 60%）的不算，那是整页。 */
    void coverIds(AccessibilityNodeInfo root, java.util.Set<String> ids, List<Block> out, int top, int bottom) {
        if (ids.isEmpty()) return;
        int sh = s.getResources().getDisplayMetrics().heightPixels;
        int sw = s.getResources().getDisplayMetrics().widthPixels;
        List<AccessibilityNodeInfo> hits = new ArrayList<>();
        for (String id : ids) hits.addAll(byId(root, id));
        for (AccessibilityNodeInfo n : hits) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.left < -5 || r.right > sw + 5) continue;   // 页面滑入动画中，还在屏幕外
            if (r.top < top) r.top = top;
            if (r.bottom > bottom) r.bottom = bottom;
            if (r.height() > 40 && r.height() < sh * 0.6) out.add(new Block(r, ""));
        }
    }

    /** 标题所在的整张卡片：往上找第一个宽度超过屏幕 85% 的父控件；比屏幕 60% 还高就不算（那是整页）。 */
    Rect cardOf(AccessibilityNodeInfo title, int sw, int sh) {
        AccessibilityNodeInfo p = title.getParent();
        for (int i = 0; i < 5 && p != null; i++) {
            Rect b = new Rect();
            p.refresh();
            p.getBoundsInScreen(b);
            if (b.width() >= sw * 0.85) return b.height() <= sh * 0.6 ? b : null;
            p = p.getParent();
        }
        return null;
    }

    /** 页面顶部固定的按钮（「我的」页的客服、设置图标等）的下边：挡板不能压上去。 */
    int headerBottom(AccessibilityNodeInfo root) {
        int sh = s.getResources().getDisplayMetrics().heightPixels;
        int bottom = 0;
        // 「我的」页顶上的客服、设置按钮
        for (String id : new String[]{"ivService", "ivSet"})
            for (AccessibilityNodeInfo n : byId(root, id)) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.top < sh * 0.12) bottom = Math.max(bottom, b.bottom);
            }
        return bottom;
    }

    /** 图标下面那行字所在的整个图标格（图标 + 文字）：往上找最近的可点击父控件，宽度不超过屏幕 40%。 */
    Rect iconCell(AccessibilityNodeInfo label, int sw) {
        AccessibilityNodeInfo p = label.getParent();
        for (int i = 0; i < 4 && p != null; i++) {
            Rect b = new Rect();
            p.refresh();
            p.getBoundsInScreen(b);
            if (b.width() > sw * 0.4) return null;
            if (p.isClickable()) return b;
            p = p.getParent();
        }
        return null;
    }

    /** 导航栏里某一格的位置：找屏幕底部 15% 以内、文字是 label 的标签，取它所在的整格。 */
    Rect navTab(AccessibilityNodeInfo root, String label) {
        int sh = s.getResources().getDisplayMetrics().heightPixels;
        int sw = s.getResources().getDisplayMetrics().widthPixels;
        AccessibilityNodeInfo t = null;
        for (AccessibilityNodeInfo n : byText(root, label, true)) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.top > sh * 0.85) { t = n; break; }
        }
        if (t == null) return null;
        Rect r = new Rect();
        AccessibilityNodeInfo cell = t;
        for (int i = 0; i < 4 && cell != null; i++) {
            Rect b = new Rect();
            cell.refresh();
            cell.getBoundsInScreen(b);
            if (b.width() > sw * 0.4) break;          // 再往上就是整条导航栏了
            r.set(b);
            if (cell.isClickable()) break;
            cell = cell.getParent();
        }
        // 文字和图标在格子里上下排，往上补到导航栏顶边，图标也盖住
        AccessibilityNodeInfo bar = firstById(root, "rv_navigation");
        if (bar != null) {
            Rect b = new Rect();
            bar.getBoundsInScreen(b);
            r.top = Math.min(r.top, b.top + 4);
            r.bottom = Math.max(r.bottom, b.bottom);
        } else {
            r.top -= 90;
        }
        return r.width() > 50 && r.height() > 50 ? r : null;
    }

    /** 顶部标题栏底边：页面往上滚时，挡板不能压到标题栏（返回键、扫一扫）上。 */
    int toolbarBottom(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo tb = firstById(root, "toolbar");
        if (tb == null) tb = firstById(root, "titleBar");
        if (tb == null) return (int) (s.getResources().getDisplayMetrics().heightPixels * 0.08);
        Rect r = new Rect();
        tb.getBoundsInScreen(r);
        return r.bottom;
    }

    /** 底部固定栏（首页的导航栏）顶边，挡板不往下盖。 */
    int bottomBarTop(AccessibilityNodeInfo root) {
        int sh = s.getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo tab = null;
        for (AccessibilityNodeInfo n : byText(root, "首页", true)) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.top > sh * 0.85) { tab = n; break; }
        }
        if (tab == null) return Integer.MAX_VALUE;
        Rect tb = new Rect();
        tab.getBoundsInScreen(tb);
        return tb.top - 30;
    }

    void addBlock(List<Block> out, AccessibilityNodeInfo n, String label, int topLimit, int bottomLimit, int minH) {
        if (!AdSkipService.fresh(n)) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.top < topLimit) r.top = topLimit;
        int sw = s.getResources().getDisplayMetrics().widthPixels;
        int sh = s.getResources().getDisplayMetrics().heightPixels;
        if (r.bottom > bottomLimit && bottomLimit > r.top + minH) r.bottom = bottomLimit;
        if (r.bottom > sh) r.bottom = sh;
        // 切标签时 ViewPager 把页面挪到屏幕外（如 [-1079,…][0,…]）；打开 app 的缩放动画里宽度不到 85%；太大等于整页
        if (r.height() < minH || r.left < -5 || r.right > sw + 5 || r.width() < sw * 0.85
                || r.height() > sh * 0.85) return;
        for (Block b : out) if (b.r.contains(r)) return;   // 已经被更大的挡板盖住
        out.add(new Block(r, label));
    }

    void show(int i, Block b) {
        while (views.size() <= i) { views.add(null); shownRects.add(null); }
        FrameLayout v = views.get(i);
        if (v == null) {
            v = new FrameLayout(s);
            // 胖乖始终是浅色，挡板不能被系统强制反色。
            if (android.os.Build.VERSION.SDK_INT >= 29) v.setForceDarkAllowed(false);
            v.setBackgroundColor(PAGE_BG);
            TextView t = new TextView(s);
            t.setTextColor(Color.parseColor("#9AA0A6"));
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            v.addView(t, new FrameLayout.LayoutParams(-1, -1));
            views.set(i, v);
        }
        if (b.quiet()) {
            v.setOnClickListener(x -> { });   // 抹掉的地方：点了什么都不发生（也就点不到下面的商城、福利助手）
        } else {
            v.setOnClickListener(x -> {
                suppressUntil = SystemClock.uptimeMillis() + 10000;
                hideAll();
                h.postDelayed(() -> updateLater(0), 10100);
            });
        }
        ((TextView) v.getChildAt(0)).setText(b.quiet() ? "" : b.r.height() >= 200 ? b.label + "\n点一下临时显示 10 秒" : b.label);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                b.r.width(), b.r.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | (b.passThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = b.r.left;
        lp.y = b.r.top;
        Rect last = shownRects.get(i);
        // 同一个序号的挡板会被不同的块复用：位置或「能不能点透」变了都要更新
        boolean wasThrough = (v.getTag() instanceof Boolean) && (Boolean) v.getTag();
        if (last == null) {
            wm.addView(v, lp);
            shownRects.set(i, new Rect(b.r));
            Log.w("cover show #" + i + " " + (b.quiet() ? "(quiet)" : b.label) + (b.passThrough ? " pass-through " : " ")
                    + b.r.toShortString() + " in " + AdSkipService.currentActivity);
        } else if (!last.equals(b.r) || wasThrough != b.passThrough) {
            wm.updateViewLayout(v, lp);
            last.set(b.r);
        }
        v.setTag(b.passThrough);
    }

    void hideAt(int i) {
        if (i < shownRects.size() && shownRects.get(i) != null) {
            try { wm.removeView(views.get(i)); } catch (Throwable ignored) { }
            shownRects.set(i, null);
            Log.w("cover hide #" + i);
        }
    }

    void hideAll() {
        for (int i = 0; i < views.size(); i++) hideAt(i);
        stopTick();
    }

    /** 在这些页面上每 0.25 秒核对一次：排版变化、广告晚加载、切标签不一定有无障碍事件。 */
    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking) return;
            updateLater(0);
            h.postDelayed(this, 400);
        }
    };

    void startTick() { if (!ticking) { ticking = true; h.postDelayed(tick, 400); } }
    void stopTick() { ticking = false; h.removeCallbacks(tick); }

    void hide() { hideAll(); }
}
