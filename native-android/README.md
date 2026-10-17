# 世界之间 / JLZ 在场 · Android

当前 Android 是 **薄客户端 + 设备执行器**：负责采集用户明确授权的设备状态、执行手机本地动作、保存本地队列，并与 JLZ Runtime 通信。ChatGPT/MCP 才是决策层。

## 当前真实链路

```
Android 原生 App
  ├─ NativeRuntimeService
  ├─ AccessibilityService / NotificationListener
  ├─ 按需截图 + 明确授权的前台应用自动截图 + 有界耐久队列
  ├─ App 门禁 / 全机专注
  ├─ 学习状态 / 位置天气 / 状态灯 / 留言
  └─ RuntimeApiClient
           ↓
JLZ Runtime /api/*
           ↓
MCP /mcp-presence
           ↓
ChatGPT
```

## 当前保留的设备能力

- Runtime 后台心跳与命令长轮询。
- 当前 App / 屏幕 / 使用时长 / 解锁等状态摘要。
- Accessibility 滚动、内容变化和窗口切换只形成轻量节奏指标（NORMAL / FAST_SCROLL / RAPID_SCROLL / ATTENTION_FRAGMENTED），不上传可见正文。
- 无障碍页面节点与明确授权的操作。
- 通知观察与低敏摘要。
- 精确 event_id/device_id 的截图上传、读取与 reviewed ACK。
- 自动截图默认关闭；原有安装保留已选应用范围，不因升级扩大授权。
- AI 明确请求和用户“给你看”仍可截图；自动截图须显式开启。设置中可另行选择“所有前台应用（敏感页面除外）”，不必逐个添加；始终保留字节预算、单 App 冷却、去重与有界队列。
- 单 App 门禁：`lock_app / unlock_app / temporary_unlock_app / deny_unlock_request`。
- 全机专注：`start_focus_mode / end_focus_mode / get_focus_status / reply_focus_request`。
- 状态灯、随手记/留言、学习桥接、日历添加、位置天气等仍在当前代码链路中。

## 已退休 / 不再维护

以下能力已经从产品链路删除，不应在 Runtime/MCP 重新暴露：

- Health Connect。
- 旧 `WearableBridge / CompositeWearableBridge` 抽象与伪 Band 8 telemetry。
- 睡眠页、白噪音/音乐播放、co-sleep 指令。
- 旧掌心窗“全局最新截图”语义。
- 多套同义 App 门禁命令（`screen_break_*` / `add_locked_app` 等）。
- 已无 Android 执行器的小金库、外卖、日记、Runtime memory/reminder 旧命令。

### 华为健康

Health Connect 已删除。当前 App 不声称直接读取华为健康数据库。
`PresenceNotificationListenerService` 可在用户授权通知访问后，从 **华为运动健康公开显示的通知文字**中提取有限的步数/热量信息；这属于通知文本观察，不是 Health Connect 或私有健康数据库接入。

## 截图规则

- 上传必须携带明确的 `X-Device-ID`。
- 每张截图使用稳定 UUID `event_id`；网络重试复用同一个 UUID。
- “上传成功”不等于 GPT 已看见；只有明确 reviewed ACK 后才可按策略释放。
- 自动截图默认关闭。显式开启后，长边不超过 1080 px、JPEG quality 75、单 App 至少冷却 18 秒；无固定日张数上限，保留每日 48 MiB、每月 768 MiB 自动流量预算及月截图流量警戒。
- 自动截图达到本地或服务端流量保护阈值后会退避；用户手动和 GPT 明确请求保留独立通路。
- 服务端索引只返回最近一页时，Android 不会把“没出现在这一页”误判成远端丢失。

### 所有前台应用模式

在原生设置的“我自己看看你在干什么”中，分别控制自动截图总开关和“所有前台应用（敏感页面除外）”。关闭范围开关后恢复原先选择的应用；新安装的普通应用无需预先登记。只采样实际前台，不读取后台应用或恢复过去的屏幕。

银行、支付、密码管理器、认证器、系统权限页及世界之间自身被排除。其他应用仍须通过每次捕获前后的页面检查：密码/验证码/付款内容、正在输入或含草稿、键盘、锁屏、混入其他应用、不可确认/过大节点树均跳过。未聚焦且无内容的普通输入框不会单独阻止阅读页。系统拒绝截图时不绕过。

CoWatch 的显式单应用授权及其更严格页面检查保持独立。切换、6 秒停留、后续停留与滚动采样仍受既有冷却和预算约束；升级不代表真机已经启用该模式。

## 同一 APK 的手机 / 平板身份

- 手机默认 `device_id=android-phone-native-n0`，`device_type=phone`。
- `smallestScreenWidthDp >= 600` 的平板默认 `device_id=android-tablet-native-n0`，`device_type=tablet`。
- 两台设备共用相同 APK、Runtime 协议、耐久上报和 App Gate 能力，但各自拥有命令队列、活动流和截图索引。
- 旧版默认手机 ID 在平板升级后会迁移到平板 ID；用户手动配置的其他设备 ID 不会被覆盖。
- Runtime 只向一个选定表面发消息：前台网页优先，其次唯一活跃 Android，最后回退手机；不会默认双端广播。

## 重要事件可靠性

- Inbox 回复和 Focus 临时放行请求统一进入 `PendingReplyStore`，不再双写独立 App Gate 请求路由。
- 通知观察、语义位置变化和信息流节奏变化共用一个 `PendingActivityEventStore`；先落本机 SQLite，再走 `/api/activity/events`。
- command report 与 screenshot 仍使用各自的耐久 outbox，因为两者有不同 ACK 和二进制保留语义；没有合并成不可靠的万能队列。

## 构建

```bash
cd native-android
gradle :app:assembleDebug --no-daemon
```

公开仓库 GitHub Actions 仍负责 APK 构建与稳定签名；本次清理分支不自动发布，合并 `main` 后才触发主线构建。

## 数据原则

本次代码清理不删除用户历史记录、截图索引、状态灯、留言、学习记录或 Supabase 数据。删除的是已经没有运行路径的代码和重复命令入口。
