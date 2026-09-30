# 世界之间 / JLZ 在场 · Android

当前 Android 是 **薄客户端 + 设备执行器**：负责采集用户明确授权的设备状态、执行手机本地动作、保存本地队列，并与 JLZ Runtime 通信。ChatGPT/MCP 才是决策层。

## 当前真实链路

```
Android 原生 App
  ├─ NativeRuntimeService
  ├─ AccessibilityService / NotificationListener
  ├─ 自动截图协调器 + 有界截图队列
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
- 无障碍页面节点与明确授权的操作。
- 通知观察与低敏摘要。
- 精确 event_id/device_id 的截图上传、读取与 reviewed ACK。
- App 自动切换 30 秒截图、停留 5 分钟截图；受本地 TTL、重试上限和服务端流量保护约束。
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
- 自动截图达到服务端应用层流量保护阈值后会退避，明确 GPT 请求的截图保留单独通路。
- 服务端索引只返回最近一页时，Android 不会把“没出现在这一页”误判成远端丢失。

## 构建

```bash
cd native-android
gradle :app:assembleDebug --no-daemon
```

公开仓库 GitHub Actions 仍负责 APK 构建与稳定签名；本次清理分支不自动发布，合并 `main` 后才触发主线构建。

## 数据原则

本次代码清理不删除用户历史记录、截图索引、状态灯、留言、学习记录或 Supabase 数据。删除的是已经没有运行路径的代码和重复命令入口。
