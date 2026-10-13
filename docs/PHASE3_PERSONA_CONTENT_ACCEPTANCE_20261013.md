# 世界之间 · 第三阶段｜人格、主页文案与原生回执

范围：Android/Pages **增量**，堆叠在第二阶段 PR #47 之上；不重建 UI，不更改 Runtime 数据库、配对、签名或启动器。

## 已修改的代码

- `WorldContentCache` 不再用单个 `pending_receipt` 代表整个原生 App，而是用 SharedPreferences 的有界 FIFO 保存每次 Compose 声明已经应用的键及 revision/view_id。原生来电页、Gate、主题切换的未发送记录不会互相覆盖。
- 回执在 Home Node 真正返回 `ok=true` 后才出队；`409` 版本冲突只清除过期那一项；失败保持待发送。出队采用首记录身份校验，UI 与网络并发不误删后续记录。
- 有界64条待发送回执；容量触顶只增加非秘密 `receipt_overflow_count`，绝不把没有发送的回执冒称已应用。已确认回执做本地去重，未确认的不能进入已确认集合。
- `WorldContentCache.save` 要求有效 revision/entries/view_id 且同步写入成功才返回 true，旧版本不得覆盖新版本。
- 现有 Windows Home Node 内容拉取从约300秒缩到约60秒，并且只在经验证的缓存保存成功时推进下次检查。网络失败允许后续轮询重试。**这不是实时 push**。
- WebShell `WorldBetweenHome.contentReceiptStatus()` 仅读取本地待回执条数/溢出计数，无 Token、配对串或截图。网页“同步与更改记录”页每15秒显示此非秘密诊断，区分内容写入、移动端画面渲染和服务端回执。
- `PresenceCallbackAdapter` 睡眠模式下改用安静通知时，真实保留 Android 通知失败回执。失败返回 false 而不是过去的假成功。
- Q人物空闲动画在安全/专注/睡眠/视频陪看等高优先级模式之下，由既有 `JlzVoiceStateEngine` 有事实来源的本地/官方状态映射：LONGING→clingy、PROUD→proud、CONCERNED→thinking；不推断用户情绪、不生成假连续意识。
- 早先 Stage6 的 Pages 欢迎语 `welcome.line`、首页 `home.moment`、分时 `greetings.*`、Gate 原生 `gate.incoming`、原生 Callback `callback.*` 仍复用既有 `WorldContent` CAS 版本系统。**不创建第二套云数据表。**

## 代码验收

- Robolectric `WorldContentCacheTest` 覆盖：缓存旧版本拒绝；多个页面断网时不覆盖；失败不出队；成功后去重；网络在途新增页面不丢；409仅丢弃旧记录；过期文案不声称显示。
- Q 版基础安全优先级、Persona 语气状态源/有效期原有测试和新增 idle 姿态回归。
- Android 构建 + lint + 单测、Pages 构建 + 文案相关单测通过后标 `CI_PASS`，未通过继续修。

## Codex 最终真机验收

1. 使用现有 Home Node 官端 `read_world_content` 读取当前 revision，显式 `expected_revision` 更新 welcome.line、home.moment、gate.incoming、callback.incoming，保持特殊字符/限制与权限校验。
2. 手机/平板 WebShell 打开欢迎页、主页；比较**实际屏幕文案**与 Home Node 当前版本。打开原生 Gate 和 Callback，核查自定义文案与离线退化。
3. 真机在离线时依次打开 Callback→Gate→主页，检查每条回执没有被另一界面覆盖；联网后多个回执出队，服务端仅代表最后一个 `device_id+surface` 最新展示记录，历史多回执须查实际网络请求或增加审计，不能把存储的单条最新回执冒充全历史。
4. 过期版本/冲突：409后用户刷新显示最新文案，不要静默当作成功；真实 OEM 通知权限禁用时 Callback 应返回拒绝/降级。
5. Q人物姿态在 NORMAL 下随经授权 official voice 状态变化，FOCUS/SLEEP、系统保护页/授权敏感页必须覆盖视觉人格。

**禁止以 CI 通过冒充 Windows 已部署、MCP 已暴露或手机已显示**。这一阶段不动已退役 Railway/Render/Neon。最终由 Codex 一次原地更新 Windows Home Node、MCP、最终签名 APK，再做实机验收。
