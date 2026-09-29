# 世界之间 · 羊皮纸童话 UI 试装版（2026-09-29）

这是针对原生 `dev.jlz.presence` Android 应用的界面试装分支，不是新的独立 APP。

- 预览包版本：`0.4.4-parchment-ui-preview`（versionCode `2026092905`）。
- 构建来源：已验证通过的公开 QAvatar V1 基线 `ci/qavatar-smart-capture-v1-20260929`，叠加私有设计分支的 UI 代码，不以旧 public/main 为基线。
- 只更改 Kotlin Compose 布局/主题/导航和 Android 系统栏，保留现有 Timeline、状态灯 V3、留言原话、学习、手机与通知能力，以及四套已经公开的 QAvatar 资源。
- 悬浮小人立绘属于先前已在这个公开构建镜像中的素材；本轮没有公开任何新的私密图片。
- 不打包任何用户聊天历史、截图、后台 token、数据库记录、私有 Runtime/MCP 服务或签名 keystore。GitHub Actions 使用已有机密在构建时进行签名。
- 单独的 pre-release 标签 `android-parchment-preview-20260929`，不覆盖正式 `android-native-latest`。

## 首次真机验收重点
打开首页与五栏导航 → 读取并发送真实聊天 → 填写可留空的状态灯四轴与亲密需求 → 留言/离线恢复补传 → 双向时间线与回响 → 悬浮角色切换、隐藏、缩放 → 粉笔答题不断线 → 通知接收和返回。发生任何数据覆盖/路由错误时暂不要用它覆盖常用版本。

`BUILD SUCCESS` 只证明源码/打包成功，不能代替用户设备的升级和功能验收。
