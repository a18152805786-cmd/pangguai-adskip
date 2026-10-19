# 胖乖去广告

自用的安卓小工具，给「胖乖生活」（`com.qiekj.user`）去广告。不需要 root，不改胖乖本体，所以微信、支付宝付款不受影响。

## 怎么做到的

两层配合：

1. **网络拦截**（`DnsVpnService`）：一个只管胖乖的本地 VPN。只有胖乖的 DNS 查询会进来，名单上的广告、直播域名直接回「不存在」，其余照常转发。别的 app 的流量不经过它。
2. **无障碍服务**（`AdSkipService` + `FeedCover`）：
   - 自动点开屏广告的「跳过」、关掉插屏和广告弹窗；
   - 网络拦不掉的部分（福利助手、商城入口、推广图标等），盖一块和页面同色的挡板抹掉。

规则按广告的共同特征来认（「跳过」字样、广告 SDK 的类名和控件 id），不绑胖乖的具体版本。

## 名单（改了不用重装）

两个文本文件推到手机上，5 秒内生效：

| 文件 | 手机上的位置 | 作用 |
|---|---|---|
| `blocklist.txt` | `/sdcard/Android/data/com.zz.pgadskip/files/blocklist.txt` | 网络拦截的域名，一行一个，`a.com` 同时拦子域名 |
| `hide.txt` | `/sdcard/Android/data/com.zz.pgadskip/files/hide.txt` | 界面上要抹掉的东西：图标名 / `卡片:标题` / `id:控件名` |

```bash
adb push blocklist.txt /sdcard/Android/data/com.zz.pgadskip/files/blocklist.txt
```

**不能拦的域名**：`youzan.com`、`youzanyun.com`（拦了胖乖会崩溃）、`qiekj.com` 整个（胖乖自己的接口）、微信 / 支付宝的付款域名。

## 构建

不用 Gradle，`build.ps1` 直接调 aapt2 → javac → d8 → zipalign → apksigner。需要：

- JDK 17
- Android build-tools 35.0.1、platform android-35

路径写在 `build.ps1` 开头（默认 `E:\DevTools`）。签名密钥放在仓库外，第一次构建时自动生成。

```powershell
.\build.ps1
adb install -r build\pgadskip.apk
```

## 排查

运行日志在手机 `/sdcard/Android/data/com.zz.pgadskip/files/log.txt`，只存在本地：

- `DNS BLOCK/allow 域名`：胖乖连了哪些地方；
- `CLICK ...`：点了什么；
- `cover show/hide`：挡板位置；
- 记录模式下还有界面结构 `DUMP`。

## 已知限制

- 开着网络拦截走付款流程还没完整测过。付款相关域名都没拦，如果卡住，先在 app 里关掉「网络拦截」。
- 手机同时只能开一个 VPN。开 Clash 时它会自动让开，胖乖下次打开时再补上。
- 小米手机要把本 app 的省电策略设为「无限制」，否则可能被后台清掉。
