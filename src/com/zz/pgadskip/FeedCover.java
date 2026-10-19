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

    static boolean isCoverPage(String act) {
        return AdSkipService.isHome(act) || AFTER_PAY.matcher(act).matches();
    }

    void updateLater(long delay) {
        if (pending) return;
        pending = true;
        h.postDelayed(() -> {
            pending = false;
            try { update(); } catch (Throwable t) { Log.w("cover error " + t); hideAll(); }
        }, delay);
    }

    void update() {
        String act = s.currentActivity;
        if (!Prefs.enabled(s) || !Prefs.cover(s) || SystemClock.uptimeMillis() < suppressUntil || !isCoverPage(act)) {
            hideAll();
            return;
        }
        AccessibilityNodeInfo root = topRoot();
        // 胖乖不在前台或有弹窗：全部收起并停掉定时核对；回来时窗口变化事件会再叫醒这里
        if (root == null) { hideAll(); return; }
        startTick();
        List<Block> blocks = findBlocks(root, act);
        for (int i = 0; i < blocks.size(); i++) show(i, blocks.get(i));
        for (int i = blocks.size(); i < views.size(); i++) hideAt(i);
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

    List<Block> findBlocks(AccessibilityNodeInfo root, String act) {
        List<Block> out = new ArrayList<>();
        int bottomLimit = bottomBarTop(root);

        // 1. 直播带货流：id 以 ll_scrolling_view 开头（首页是 ll_scrolling_view，启动页是 ll_scrolling_view_mall）
        List<AccessibilityNodeInfo> feeds = AdSkipService.findAll(root,
                n -> n.isVisibleToUser() && AdSkipService.idOf(n).startsWith("ll_scrolling_view"), 3);
        if (feeds.isEmpty()) {
            // 以后改了 id：从「进入直播间」「直播中」往上找最近一层能滚动的容器
            AccessibilityNodeInfo mark = AdSkipService.find(root, n -> {
                CharSequence t = n.getText();
                return n.isVisibleToUser() && t != null && ("进入直播间".contentEquals(t) || "直播中".contentEquals(t));
            });
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
            AccessibilityNodeInfo w = AdSkipService.find(root,
                    n -> n.isVisibleToUser() && "welfare_assistant".equals(AdSkipService.idOf(n)));
            if (w == null) {
                AccessibilityNodeInfo t = AdSkipService.find(root, n -> {
                    CharSequence x = n.getText();
                    return n.isVisibleToUser() && x != null && "胖乖福利助手".contentEquals(x);
                });
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
                for (AccessibilityNodeInfo label : AdSkipService.findAll(root, n -> {
                    CharSequence x = n.getText();
                    if (!n.isVisibleToUser() || x == null) return false;
                    String t = x.toString().trim();
                    return icons.contains(t) || cards.contains(t);
                }, 20)) {
                    if (!AdSkipService.fresh(label)) continue;
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
            for (AccessibilityNodeInfo n : AdSkipService.findAll(root,
                    x -> x.isVisibleToUser() && ids.contains(AdSkipService.idOf(x)), 6)) {
                if (!AdSkipService.fresh(n)) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.top < headerBottom) r.top = headerBottom;
                if (r.bottom > bottomLimit) r.bottom = bottomLimit;
                if (r.height() > 40 && r.height() < sh * 0.6) out.add(new Block(r, ""));
            }
        }

        if (AFTER_PAY.matcher(act).matches()) {
            // 2. 推荐商品卡：id 认得出来就用 id，否则找「推荐好物」这类标题，取它所在的整张卡
            List<AccessibilityNodeInfo> recs = AdSkipService.findAll(root,
                    n -> n.isVisibleToUser() && RECOMMEND_ID.matcher(AdSkipService.idOf(n)).matches(), 3);
            if (recs.isEmpty()) {
                AccessibilityNodeInfo title = AdSkipService.find(root, n -> {
                    CharSequence t = n.getText();
                    return n.isVisibleToUser() && t != null && t.length() < 20 && RECOMMEND_TITLE.matcher(t).matches();
                });
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
        for (AccessibilityNodeInfo n : AdSkipService.findAll(root, x -> {
            if (!x.isVisibleToUser() || !x.isClickable()) return false;
            Rect b = new Rect();
            x.getBoundsInScreen(b);
            return b.top < sh * 0.12 && b.height() < sh * 0.1 && b.height() > 0;
        }, 10)) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            bottom = Math.max(bottom, b.bottom);
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
        AccessibilityNodeInfo t = AdSkipService.find(root, n -> {
            CharSequence x = n.getText();
            if (!n.isVisibleToUser() || x == null || !label.contentEquals(x)) return false;
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            return b.top > sh * 0.85;
        });
        if (t == null || !AdSkipService.fresh(t)) return null;
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
        AccessibilityNodeInfo bar = AdSkipService.find(root, n -> n.isVisibleToUser() && "rv_navigation".equals(AdSkipService.idOf(n)));
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
        AccessibilityNodeInfo tb = AdSkipService.find(root, n -> n.isVisibleToUser()
                && ("toolbar".equals(AdSkipService.idOf(n)) || "titleBar".equals(AdSkipService.idOf(n))));
        if (tb == null) return (int) (s.getResources().getDisplayMetrics().heightPixels * 0.08);
        Rect r = new Rect();
        tb.getBoundsInScreen(r);
        return r.bottom;
    }

    /** 底部固定栏（首页的导航栏）顶边，挡板不往下盖。 */
    int bottomBarTop(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo tab = AdSkipService.find(root, n -> {
            CharSequence t = n.getText();
            return n.isVisibleToUser() && t != null && "首页".contentEquals(t);
        });
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
                h.postDelayed(this::update, 10100);
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
                    + b.r.toShortString() + " in " + s.currentActivity);
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
            h.postDelayed(this, 250);
        }
    };

    void startTick() { if (!ticking) { ticking = true; h.postDelayed(tick, 250); } }
    void stopTick() { ticking = false; h.removeCallbacks(tick); }

    void hide() { hideAll(); }
}
