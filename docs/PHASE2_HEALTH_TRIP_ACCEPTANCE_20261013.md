# 世界之间｜缺口补完第二阶段 · 华为健康 + GPS 同行

状态：Android PR #47（基于第一阶段 PR #46）**源码施工 / 自动测试**，未部署 Windows Home Node、未安装新版 APK、未验证厂商实机行为。

## 华为健康

- 08:05 是既有 Android `setAndAllowWhileIdle` 非精确提醒/检查；Doze 可延后，不能宣传精准分钟触发。
- 只能从已授权且**真实可见**的 `com.huawei.health` 屏幕文本解析睡眠时长。通知文字仍只供标识步数等已观察内容。
- 增加显式的入睡日期/起床日期校验：仅用户可见页面中与北京当日/前一夜吻合的证据才标记 `sleep_date_verified=true`。日期缺失保留独立的 `sleep_visible_date_unverified` 状态，错日直接拒绝更新睡眠分钟。
- 回执 `morning_check_result` 可能为 `checking_visible_sources`、`huawei_page_not_visible`、`visible_source_check_failed`、`sleep_duration_missing`、`visible_page_wrong_date`、`sleep_visible_date_unverified`、`sleep_visible_verified`，不再静默冒充读取成功。
- **不强制唤醒/解锁手机、不强开华为健康、不伪造昨夜睡眠；普通打开 Huawei 健康 APP 也不保证其 UI 文本可被系统无障碍读到**。要获取稳定、真实的睡眠数据，下一轮需先核实 Huawei 官方授权接口 / Health Connect 实际是否可用，不能假定有权限。
- 18:30 低步数提醒保留既有来源和三小时新鲜度规则；没有数据不能解释为零步。

## 自愿同行 GPS

- 必须用户显式点击启动精确位置前台服务；`ACCESS_FINE_LOCATION` 不可用则停止。绝不为迎合功能绕过 Android 权限或永久后台共享。
- 同一已启动会话不再因为离线40秒被强行关闭；在前台服务存续期间每20秒重新使用现有已配对 Home Node URL / Token 尝试连接，同 session ID、同 idempotent 点位 ID 补传。
- 上传进度仅在 Runtime 明确 `ok=true` 且返回**完全匹配、有序、同数量的 point_ids** 时推进；单靠 URL 可连接、200状态码或返回数量不足不能认定同步完成。
- 本地精确坐标只存在 2000 点上限的进程内 RAM（不写 App Preferences/活动日志/GitHub），并有24小时主动授权上限。达上限将停止接受新点并显示已达容量，而不是悄悄滚动丢掉旧点。屏幕通知明确当前在线/断网重试/容量限制；结束后保留非坐标的未上传点数回执。
- 如果 App 进程被 Android 系统杀死、用户主动结束或行程满24小时，未确认补传的精确点**可能丢失**；不冒充“零丢失”。进程死亡后不会自动恢复精确共享；需用户再次主动授权。
- 后端既有 `TripStore` 也限制每段 2000 点、最多24小时且仅用户显式会话可分享，此阶段不改后端数据保留与存储格式。

## 自动回归与 Codex 最终验收

1. Robolectric 检测：可见夜间睡眠文本缺日期/日期吻合/日期冲突/隔日过期、08:05 没有可见来源时原因明确。
2. JVM 检测：GPS batch 点位 ID 必须精确回显；缺字段/错序/错数量/虚假 ok 不推进；超过24小时退出。
3. Android 构建 + 单测 + 签名验证通过才标记 `CI_PASS`；否则继续修，不创建中间发布版。
4. 真机故障注入：用户主动开启一次 GPS，同一会话断网>40秒恢复后旧点补传且顺序不乱；断网超容量有可见提示；进程死亡后位置不会无授权自动开启；关闭定位或到24h后确实结束。测试需保留用户真实数据，不能清空。
5. 08:05 华为健康测试在屏幕锁定、华为在前台、仅步数通知三个场景分别检查回执；没有 UI 的场景必须明确 `unavailable`，不允许从手机开关屏推算睡眠。
6. 本机 Windows Home Node 尚未安装最新 Runtime/MCP；第三、四阶段结束后**只一次**交 Codex 作安全备份、Windows Home Node 部署、官端 MCP 工具刷新及手机/平板一轮最终 APK 覆盖安装。

架构：只有 Windows Home Node + Tailscale Funnel 为唯一 Runtime/MCP；Railway、Render、Neon 旧计算端继续退役。
