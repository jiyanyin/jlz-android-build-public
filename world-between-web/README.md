# 世界之间 Web

这个静态 PWA 是用户主动记录与网页聊天界面。它不持有 Android/MCP 控制令牌。

首次打开后，在“更多 → 网页与 Runtime”填写 Runtime HTTPS 地址与单独的 `X-Web-Token`。状态灯、随手记、此刻我在、共历和聊天会先写浏览器本地状态；网络失败时同时进入本地 outbox，恢复后继续同步到 `space_id=world-between-primary`。

网页可见且最近有真实操作时，Runtime 可以把一条消息只路由到网页；否则选择唯一活跃 Android，最后回退手机。网页前台每 30 秒只交换轻量 JSON。

当前实现是前台消息弹窗。浏览器完全关闭后的系统通知需要 service worker、Push subscription 存储和 VAPID，尚未实现。

本地检查：

```bash
node --check runtime-client.js
node --check app.js
```
