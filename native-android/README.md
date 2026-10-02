# 世界之间 / JLZ 在场 · Android

当前 Android 是 **薄客户端 + 设备执行器**：负责采集用户明确授权的设备状态、执行手机本地动作、保存本地队列，并与 JLZ Runtime 通信。ChatGPT/MCP 才是决策层。

## 当前真实链路

```
Android 原生 App
  ├─ NativeRuntimeService
  ├─ AccessibilityService / NotificationListener
  ├─ 按需截图 + 少量白名单自动截图 + 有界耐久队列
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
- App 切换与停留默认只产生结构化事件，不会自动截图。
- AI 明确请求和用户“给你看”仍可截图；自动截图必须显式开启，并受张数、字节、单 App 冷却和月度预算共同约束。
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
- 自动截图默认关闭。显式开启后，长边不超过 1080 px、JPEG quality 75、每天至多 24 张、单 App 至少冷却 30 分钟，并以实际字节预算作为最终闸门。
- 自动截图达到本地或服务端流量保护阈值后会退避；用户手动和 GPT 明确请求保留独立通路。
- 服务端索引只返回最近一页时，Android 不会把“没出现在这一页”误判成远端丢失。

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
