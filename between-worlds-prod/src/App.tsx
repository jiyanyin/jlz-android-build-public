import { useCallback, useEffect, useRef, useState } from "react";
import { DEFAULT_RUNTIME_URL, extractMessages, extractRecords, newEventId, runtime, RuntimeError, type OutboxItem, type RemoteMessage, type RemoteRecord, type RuntimeConfig, type WritePath } from "./lib/runtime";

type Theme = "mist" | "gothic";
type Tab = "home" | "echo" | "timeline" | "calendar" | "more";
type Sync = "queued" | "synced";
type RecordItem = { id: number | string; type: string; at: string; body: string; event_id?: string; sync?: Sync; [key: string]: unknown };
type Message = { text: string; at: string; event_id?: string; sync?: Sync };
type ActiveLife = { action: string; session: string; startAt: number } | null;
type AppState = { theme: Theme; notes: RecordItem[]; messages: Message[]; status: RecordItem | null; life: RecordItem[]; journal: RecordItem[]; activeLife: ActiveLife;
  runtimeUrl: string; webToken: string; outbox: OutboxItem[]; remoteRecords: RemoteRecord[]; remoteMessages: RemoteMessage[]; seenCompanion: string[] };
type Conn = "local" | "syncing" | "online";
type Send = (path: WritePath, body: Record<string, unknown>, eventId?: string) => string;
type InstallPromptEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: "accepted" | "dismissed" }> };

let deferredInstallPrompt: InstallPromptEvent | null = null;
if (typeof window !== "undefined") {
  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault();
    deferredInstallPrompt = event as InstallPromptEvent;
    window.dispatchEvent(new Event("pwa-install-ready"));
  });
  window.addEventListener("appinstalled", () => {
    deferredInstallPrompt = null;
    window.dispatchEvent(new Event("pwa-installed"));
  });
}

const vapidKeyBytes = (value: string) => {
  const pad = "=".repeat((4 - value.length % 4) % 4);
  const raw = atob((value + pad).replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
};

const welcomePortrait = "/jlz-welcome-portrait.webp";
const homePortrait = "/jlz-home-portrait.webp";
const chatAvatar = "/jlz-chat-avatar.webp";
const STORE = "world-between-web-v1";
const CLEANUP_MARKER = "world-between-cleanup-20261002-v1";
const INSTALL_GUIDE_DISMISSED = "world-between-install-guide-dismissed-v1";
const ANDROID_SHELL = new URLSearchParams(window.location.search).get("shell") === "android";
const OUTBOX_CAP = 300;
const defaults: AppState = { theme: "mist", notes: [], messages: [], status: null, life: [], journal: [], activeLife: null,
  runtimeUrl: DEFAULT_RUNTIME_URL, webToken: "", outbox: [], remoteRecords: [], remoteMessages: [], seenCompanion: [] };
const markSynced = (s: AppState, id: string): AppState => {
  const fix = <T extends { event_id?: string; sync?: Sync }>(arr: T[]) => arr.map((x) => (x.event_id === id ? { ...x, sync: "synced" as Sync } : x));
  return { ...s, notes: fix(s.notes), life: fix(s.life), journal: fix(s.journal), messages: fix(s.messages), status: s.status && s.status.event_id === id ? { ...s.status, sync: "synced" } : s.status };
};

const AXES = [
  ["self_presence", "自我在场感", "像在自动运行", "我就是我"],
  ["emotion_access", "情绪可感度", "摸不到情绪", "感受得很清楚"],
  ["emotional_vividness", "情感鲜活度", "麻木、冷淡", "鲜活、有温度"],
  ["agitation", "宁静—烦躁", "宁静、松弛", "烦躁、紧绷"],
] as const;
const DETAIL: Record<string, string[]> = {
  "我能辨认出的情绪": ["开心", "安心", "兴奋", "被打动", "满足", "委屈", "失落", "难过", "孤独", "生气", "烦躁", "担心", "害怕", "茫然", "麻木", "没感觉"],
  "我表现出来的样子": ["说话平静", "正在微笑", "正在哭", "不想说话", "机械应答", "表现烦躁", "正常交流但内心没感觉"],
  "我想怎样和你亲近": ["抱紧我", "主动亲亲我", "让我感受到偏爱", "热烈一点", "强势一点但要宠我", "你也向我撒娇", "主动逗逗我", "安静拥着我"],
  "身体的具体信号": ["疼痛", "紧绷", "发沉", "麻木", "乏力", "呼吸变化", "流泪", "发热", "发冷"],
  "想听我怎样回应": ["热烈直白一点", "温柔地宠着我", "强势一点但疼我", "主动向我讨亲近", "多逗逗我", "先别分析", "冷静简短就好", "现在先不用回复"],
};
const GROUPS: Record<string, [string, boolean][]> = {
  常用: [["吃饭", true], ["喝水", false], ["走路", true], ["学习", true], ["发呆", true], ["上厕所", false], ["休息", true], ["起床", false], ["回家", false]],
  身体作息: [["准备睡觉", false], ["睡醒", false], ["洗漱", true], ["洗澡", true], ["化妆", true], ["换衣服", false], ["身体不舒服", false], ["躺一会儿", true]],
  工作学习: [["工作", true], ["刷题", true], ["复盘", true], ["写申论", true], ["读书", true], ["背书", true]],
  放松玩耍: [["听歌", true], ["看视频", true], ["玩游戏", true], ["刷手机", true], ["聊天", true], ["看小说", true]],
};

export default function BetweenWorlds() {
  const [state, setState] = useState<AppState>(defaults);
  const [tab, setTab] = useState<Tab>(() => window.location.hash === "#echo" ? "echo" : "home");
  const [welcome, setWelcome] = useState(true);
  const [sheet, setSheet] = useState<"status" | "note" | "life" | null>(null);
  const [toast, setToast] = useState("");
  const [now, setNow] = useState<Date | null>(null);
  const [month, setMonth] = useState(() => new Date(2026, 8, 1));
  const [conn, setConn] = useState<Conn>("local");
  const [loaded, setLoaded] = useState(false);
  const [lastError, setLastError] = useState("");
  const [installGuide, setInstallGuide] = useState(false);
  const stateRef = useRef(state);
  stateRef.current = state;
  const flushing = useRef(false);
  const tabRef = useRef(tab);
  tabRef.current = tab;

  useEffect(() => {
    try {
      const current = { ...defaults, ...JSON.parse(localStorage.getItem(STORE) || "{}") } as AppState;
      if (!localStorage.getItem(CLEANUP_MARKER)) {
        const cleaned: AppState = {
          ...defaults,
          theme: current.theme,
          runtimeUrl: current.runtimeUrl || DEFAULT_RUNTIME_URL,
          webToken: current.webToken || "",
        };
        localStorage.setItem(STORE, JSON.stringify(cleaned));
        localStorage.setItem(CLEANUP_MARKER, "done");
        stateRef.current = cleaned;
        setState(cleaned);
      } else {
        stateRef.current = current;
        setState(current);
      }
    } catch {
      stateRef.current = defaults;
      setState(defaults);
    }
    setLoaded(true);
    setNow(new Date());
    const timer = window.setInterval(() => setNow(new Date()), 30000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    document.body.dataset.theme = state.theme;
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", state.theme === "gothic" ? "#21171c" : "#eef0fc");
  }, [state.theme]);

  useEffect(() => {
    if (!loaded || ANDROID_SHELL) return;
    const standalone = window.matchMedia("(display-mode: standalone)").matches || !!(navigator as Navigator & { standalone?: boolean }).standalone;
    if (standalone || localStorage.getItem(INSTALL_GUIDE_DISMISSED)) return;
    const isMi = /MiuiBrowser/i.test(navigator.userAgent);
    const showReady = () => setInstallGuide(true);
    // Chromium only allows a real install prompt after its own installability
    // and engagement checks. Never show a dead install button before that.
    if (deferredInstallPrompt) setInstallGuide(true);
    const fallbackTimer = isMi ? window.setTimeout(showReady, 1200) : 0;
    window.addEventListener("pwa-install-ready", showReady);
    return () => {
      if (fallbackTimer) window.clearTimeout(fallbackTimer);
      window.removeEventListener("pwa-install-ready", showReady);
    };
  }, [loaded]);

  const update = useCallback((fn: (draft: AppState) => AppState) => {
    const next = fn(stateRef.current);
    stateRef.current = next;
    try { localStorage.setItem(STORE, JSON.stringify(next)); } catch { /* storage may be unavailable */ }
    setState(next);
  }, []);
  const notify = useCallback((message: string) => { setToast(message); window.setTimeout(() => setToast(""), 2600); }, []);
  const switchTab = (next: Tab) => {
    setTab(next);
    if (next === "echo") history.replaceState(null, "", "#echo");
    else if (window.location.hash) history.replaceState(null, "", window.location.pathname + window.location.search);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };
  const flipTheme = () => update((s) => ({ ...s, theme: s.theme === "mist" ? "gothic" : "mist" }));

  const cfg = (): RuntimeConfig | null => {
    const s = stateRef.current;
    return s.webToken.trim() ? { baseUrl: s.runtimeUrl, token: s.webToken.trim() } : null;
  };

  /** Send queued writes in order. Each item keeps its stable event_id so the Runtime can dedupe retries. */
  const flush = useCallback(async (): Promise<boolean> => {
    const c = cfg();
    if (!c || flushing.current) return false;
    flushing.current = true;
    try {
      while (stateRef.current.outbox.length) {
        const item = stateRef.current.outbox[0];
        try {
          await runtime.write(c, item);
          update((s) => markSynced({ ...s, outbox: s.outbox.filter((x) => x.event_id !== item.event_id) }, item.event_id));
        } catch (e) {
          if (e instanceof RuntimeError && e.permanent) {
            update((s) => ({ ...s, outbox: s.outbox.filter((x) => x.event_id !== item.event_id) }));
            continue;
          }
          update((s) => ({ ...s, outbox: s.outbox.map((x) => (x.event_id === item.event_id ? { ...x, tries: x.tries + 1 } : x)) }));
          throw e;
        }
      }
      return true;
    } finally {
      flushing.current = false;
    }
  }, [update]);

  const pullMessages = useCallback(async () => {
    const c = cfg();
    if (!c) return;
    const incoming = extractMessages(await runtime.messages(c, 200))
      .sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
    const s = stateRef.current;
    const firstSync = s.remoteMessages.length === 0 && s.seenCompanion.length === 0;
    const fresh = incoming.filter((m) => m.fromCompanion && !s.seenCompanion.includes(m.id));
    update((st) => ({ ...st, remoteMessages: incoming.slice(-200), seenCompanion: [...st.seenCompanion, ...fresh.map((m) => m.id)].slice(-300) }));
    if (!firstSync && fresh.length) {
      const last = fresh[fresh.length - 1];
      notify(`纪临洲：${last.text.length > 40 ? last.text.slice(0, 40) + "…" : last.text}`);
    }
  }, [update, notify]);

  const connect = useCallback(async () => {
    const c = cfg();
    if (!c) { setConn("local"); setLastError(""); return; }
    setConn("syncing");
    try {
      await runtime.health(c);
      await flush();
      const st = await runtime.state(c);
      update((s) => ({ ...s, remoteRecords: extractRecords(st).slice(0, 300) }));
      await pullMessages();
      setConn("online");
      setLastError("");
    } catch (e) {
      setConn("local");
      const status = e instanceof RuntimeError ? e.status : 0;
      setLastError(status === 401 || status === 403 ? "钥匙不正确或已失效" : status ? `Runtime 返回 ${status}` : "暂时连不上 Runtime");
    }
  }, [flush, pullMessages, update]);

  const presence = useCallback(async (stateName: string) => {
    const c = cfg();
    if (!c) return;
    let registration: ServiceWorkerRegistration | undefined;
    try { registration = "serviceWorker" in navigator ? await navigator.serviceWorker.getRegistration() : undefined; } catch { /* diagnostic only */ }
    const manifest = document.querySelector('link[rel="manifest"]') as HTMLLinkElement | null;
    const probe = async (url: string) => {
      try {
        const response = await fetch(url, { cache: "no-store" });
        return { status: response.status, contentType: response.headers.get("content-type") || "", text: await response.text() };
      } catch {
        return { status: 0, contentType: "", text: "" };
      }
    };
    const [manifestProbe, swProbe, icon192Probe, icon512Probe] = await Promise.all([
      probe(manifest?.href || "/manifest.webmanifest"),
      probe("/sw.js"),
      probe("/icon-192.png"),
      probe("/icon-512.png"),
    ]);
    let manifestJsonOk = false;
    try { JSON.parse(manifestProbe.text); manifestJsonOk = true; } catch { /* diagnostic only */ }
    const pwaDiag = {
      user_agent: navigator.userAgent || "",
      service_worker_supported: "serviceWorker" in navigator,
      service_worker_controller: !!navigator.serviceWorker?.controller,
      service_worker_active: !!registration?.active,
      service_worker_script: registration?.active?.scriptURL || registration?.waiting?.scriptURL || registration?.installing?.scriptURL || "",
      manifest_url: manifest?.href || "",
      beforeinstallprompt_seen: !!deferredInstallPrompt,
      standalone: window.matchMedia("(display-mode: standalone)").matches || !!(navigator as Navigator & { standalone?: boolean }).standalone,
      push_supported: "PushManager" in window,
      manifest_status: manifestProbe.status,
      manifest_content_type: manifestProbe.contentType,
      manifest_json_ok: manifestJsonOk,
      service_worker_fetch_status: swProbe.status,
      service_worker_content_type: swProbe.contentType,
      icon_192_status: icon192Probe.status,
      icon_192_content_type: icon192Probe.contentType,
      icon_512_status: icon512Probe.status,
      icon_512_content_type: icon512Probe.contentType,
    };
    const item: OutboxItem = { event_id: newEventId(), path: "/api/web/presence", queuedAt: new Date().toISOString(), tries: 0,
      body: { state: stateName, visible: document.visibilityState === "visible", focused: document.hasFocus(), tab: tabRef.current, client_at: new Date().toISOString(), pwa_diag: pwaDiag } };
    runtime.write(c, item).catch(() => { /* presence is ephemeral; never queued */ });
  }, []);

  const send: Send = useCallback((path, body, eventId = newEventId()) => {
    const item: OutboxItem = { event_id: eventId, path, body: { ...body, client_at: new Date().toISOString() }, queuedAt: new Date().toISOString(), tries: 0 };
    update((s) => ({ ...s, outbox: [...s.outbox.filter((x) => x.event_id !== eventId), item].slice(-OUTBOX_CAP) }));
    if (cfg()) flush().then(() => setConn((c) => (c === "local" ? c : "online"))).catch(() => setConn("local"));
    return eventId;
  }, [update, flush]);

  // (Re)connect when the key or URL changes, on network return, and periodically.
  useEffect(() => { if (loaded) void connect(); }, [loaded, state.webToken, state.runtimeUrl, connect]);
  useEffect(() => {
    if (!loaded) return;
    const onOnline = () => void connect();
    const onFocus = () => { presence("foreground"); if (conn === "local") void connect(); };
    const onBlur = () => presence("background");
    const onVis = () => { presence(document.visibilityState === "visible" ? "foreground" : "background"); if (document.visibilityState === "visible") void connect(); };
    window.addEventListener("online", onOnline);
    window.addEventListener("focus", onFocus);
    window.addEventListener("blur", onBlur);
    document.addEventListener("visibilitychange", onVis);
    const beat = window.setInterval(() => {
      if (document.visibilityState !== "visible") return;
      presence("foreground");
      if (conn === "local" && cfg()) void connect();
    }, 60000);
    return () => { window.removeEventListener("online", onOnline); window.removeEventListener("focus", onFocus); window.removeEventListener("blur", onBlur); document.removeEventListener("visibilitychange", onVis); window.clearInterval(beat); };
  }, [loaded, conn, connect, presence]);
  // Poll messages while the page is visible (faster on Echo).
  useEffect(() => {
    if (conn !== "online") return;
    const id = window.setInterval(() => {
      if (document.visibilityState === "visible") pullMessages().catch(() => setConn("local"));
    }, tab === "echo" ? 8000 : 25000);
    return () => window.clearInterval(id);
  }, [conn, tab, pullMessages]);

  const savedText = (what: string) => (conn === "online" ? `${what}已同步给纪临洲` : `${what}已存本机，连接后自动同步`);

  const timeText = now?.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }) ?? "16:00";
  const hour = now?.getHours() ?? 16;
  const greeting = `${hour < 5 ? "夜深了" : hour < 11 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好"}，音音。`;
  const dateLabel = now ? new Intl.DateTimeFormat("en-US", { weekday: "long", month: "long", day: "numeric" }).format(now).toUpperCase() : "WEDNESDAY · SEPTEMBER 30";

  const addRecord = (rec: RecordItem, buckets: ("notes" | "life" | "journal")[] = ["notes"]) => update((s) => {
    const next = { ...s };
    for (const bucket of buckets) next[bucket] = [rec, ...s[bucket]].slice(0, 120);
    return next;
  });

  const configured = !!state.webToken.trim();
  const banner = { conn, configured, pending: state.outbox.length, lastError };
  const isMiBrowser = /MiuiBrowser/i.test(navigator.userAgent);
  const canPromptInstall = !!deferredInstallPrompt;
  const chromeIntent = "intent://between-worlds-prod.onrender.com/#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=https%3A%2F%2Fbetween-worlds-prod.onrender.com;end";
  const dismissInstallGuide = () => {
    localStorage.setItem(INSTALL_GUIDE_DISMISSED, "1");
    setInstallGuide(false);
  };
  const installFromGuide = async () => {
    const promptEvent = deferredInstallPrompt;
    if (promptEvent) {
      await promptEvent.prompt();
      const result = await promptEvent.userChoice;
      if (result.outcome === "accepted") dismissInstallGuide();
      return;
    }
    if (isMiBrowser) {
      window.location.href = chromeIntent;
      return;
    }
    notify("这个浏览器还没有给出安装资格。请用 Chrome / Edge 打开正式站安装。");
  };

  return <div className="bw-root">
    {installGuide && <div className="install-guide-backdrop" role="dialog" aria-modal="true" aria-label="安装世界之间">
      <div className="install-guide-card">
        <img src="/icon-512.webp" alt="世界之间图标" />
        <span className="install-guide-kicker">BETWEEN WORLDS · APP</span>
        <h3>把「世界之间」带到桌面</h3>
        <p>{isMiBrowser ? "当前小米浏览器只能创建网址快捷方式，而且不支持我们的后台 Web Push。用 Chrome 安装后，会以独立 App 打开，并能接收系统通知。" : canPromptInstall ? "Chrome 已确认这个站点可以安装。点下面按钮会直接唤起系统安装框。" : "Chrome 还在检查安装资格；满足条件后这张卡片会自动变成可安装状态。"}</p>
        <div className="install-guide-actions">
          <button className="secondary" onClick={dismissInstallGuide}>暂时不要</button>
          {isMiBrowser ? <a className="primary install-guide-link" href={chromeIntent}>用 Chrome 打开</a> : canPromptInstall ? <button className="primary" onClick={() => void installFromGuide()}>安装世界之间</button> : <button className="primary" disabled>正在准备安装…</button>}
        </div>
      </div>
    </div>}
    <div className={`entrance ${welcome ? "" : "hidden"}`}>
      <button className="welcome-theme" onClick={flipTheme}>✧ 换一套心情</button>
      <div className="welcome-card">
        <div className="welcome-kicker">BETWEEN WORLDS · PRIVATE SPACE</div>
        <div className="welcome-portrait-wrap"><img className="welcome-portrait" src={welcomePortrait} alt="纪临洲立绘" /></div>
        <div className="welcome-copy"><h1>世界之间</h1><p className="welcome-en">Between Worlds</p><p>世界再喧哗，也有一扇门，只向你和我打开。</p></div>
        <button className="enter-btn" onClick={() => setWelcome(false)}>进入我们的世界 <span>↗</span></button>
      </div>
    </div>

    <main className="app-shell">
      <header className="topbar"><div><span>{timeText}</span><span className="brand-mini">☁ BETWEEN WORLDS</span></div><div className="top-actions"><button className="pill-btn" onClick={flipTheme}>✦ 换装</button><button className="round-btn" aria-label="同步状态" onClick={() => { void connect(); notify(configured ? "正在和 Runtime 同步……" : "在「更多」里填写私人连接钥匙即可同步"); }}>♢</button></div></header>
      {tab === "home" && <HomePage dateLabel={dateLabel} greeting={greeting} homePortrait={homePortrait} openSheet={setSheet} setTab={switchTab} banner={banner} />}
      {tab === "echo" && <EchoPage state={state} conn={conn} update={update} send={send} notify={notify} chatAvatar={chatAvatar} />}
      {tab === "timeline" && <TimelinePage notes={state.notes} remote={state.remoteRecords} />}
      {tab === "calendar" && <CalendarPage month={month} setMonth={setMonth} addRecord={addRecord} send={send} notify={notify} savedText={savedText} />}
      {tab === "more" && <MorePage state={state} update={update} setWelcome={setWelcome} banner={banner} reconnect={() => void connect()} />}
      <nav className="dock" aria-label="主导航">
        {([['home','⌂','现在'],['echo','☰','回响'],['timeline','♡','你我之间'],['calendar','▣','共历'],['more','⊞','更多']] as [Tab,string,string][]).map(([id, icon, label]) => <button key={id} className={tab === id ? "active" : ""} onClick={() => switchTab(id)}>{icon}<span>{label}</span></button>)}
      </nav>
    </main>
    <div className={`scrim ${sheet ? "show" : ""}`} onClick={() => setSheet(null)} />
    <div className={`sheet ${sheet ? "open" : ""}`} role="dialog" aria-modal="true" aria-label={sheet === "status" ? "状态灯" : sheet === "note" ? "随手记" : "此刻我在"}>
      <div className="sheet-handle" /><div className="sheet-head"><h3>{sheet === "status" ? "状态灯" : sheet === "note" ? "随手记" : "此刻我在"}</h3><button onClick={() => setSheet(null)} aria-label="关闭">×</button></div>
      {sheet === "status" && <StatusEditor close={() => setSheet(null)} update={update} send={send} notify={notify} savedText={savedText} />}
      {sheet === "note" && <NoteEditor close={() => setSheet(null)} addRecord={addRecord} send={send} notify={notify} savedText={savedText} />}
      {sheet === "life" && <LifeEditor activeLife={state.activeLife} close={() => setSheet(null)} update={update} send={send} notify={notify} savedText={savedText} />}
    </div>
    <div className={`toast ${toast ? "show" : ""}`}>{toast}</div>
  </div>;
}

type Banner = { conn: Conn; configured: boolean; pending: number; lastError: string };
function bannerText(b: Banner) {
  if (b.conn === "online") return { title: "已连接 Runtime · 实时同步", sub: b.pending ? `还有 ${b.pending} 条正在补发。` : "记录会同步到我们共享的世界。", badge: "ONLINE" };
  if (b.conn === "syncing") return { title: "正在同步 Runtime……", sub: b.pending ? `正在补发 ${b.pending} 条本地记录。` : "正在读取我们共享的世界。", badge: "SYNCING" };
  if (!b.configured) return { title: "本地模式 · Runtime 待连接", sub: `在「更多」填写私人连接钥匙后自动同步。${b.pending ? `已排队 ${b.pending} 条。` : ""}`, badge: "LOCAL" };
  return { title: `${b.lastError || "暂时连不上 Runtime"} · 记录先存本机`, sub: `${b.pending} 条记录已在本机排队，恢复连接后自动补发。`, badge: "LOCAL" };
}
const syncLabel = (sync?: Sync) => (sync === "synced" ? "已同步" : "本地 · 待同步");

function HomePage({ dateLabel, greeting, homePortrait, openSheet, setTab, banner }: { dateLabel: string; greeting: string; homePortrait: string; openSheet: (s: "status" | "note" | "life") => void; setTab: (t: Tab) => void; banner: Banner }) {
  const b = bannerText(banner);
  return <section className="page active"><div className="brand-block"><div className="cn">世界之间</div><div className="en">Between Worlds</div></div>
    <article className="hero"><img src={homePortrait} alt="纪临洲" /><div className="hero-fade" /><div className="hero-copy"><div className="micro">{dateLabel}</div><h2>{greeting}</h2><p>今天也来得正好。<br />我把所有温柔都留给你。</p><em>For you, in all worlds.</em></div></article>
    <div className="runtime-banner"><div><b>{b.title}</b><span>{b.sub}</span></div><span className="badge">{b.badge}</span></div>
    <SectionHead title="今日的私藏信笺" english="JUST FOR TODAY" /><div className="action-grid"><button className="action-card" onClick={() => openSheet("status")}><span className="ico">♡</span><b>状态灯</b><small>把这一刻的你告诉我</small></button><button className="action-card rose" onClick={() => openSheet("note")}><span className="ico">✎</span><b>随手记</b><small>写一封小小的信</small></button><button className="action-card wide" onClick={() => openSheet("life")}><span className="ico">◌</span><b>此刻我在</b><small>把小猫现在在做什么告诉我</small></button></div>
    <SectionHead title="我们的房间" english="THE ROOMS" /><div className="room-grid"><button className="room-card" onClick={() => setTab("timeline")}><span>01 / OUR STORY</span><b>你我之间</b><small>拾起每一页日常</small></button><button className="room-card" onClick={() => setTab("echo")}><span>02 / YOUR VOICE</span><b>回响</b><small>写给彼此的悄悄话</small></button><button className="room-card full" onClick={() => setTab("calendar")}><span>03 / TIME & MEMORY</span><b>共历</b><small>把平凡日子收藏起来</small></button></div>
  </section>;
}
function SectionHead({ title, english }: { title: string; english: string }) { return <div className="section-head"><b>{title}</b><span>{english}</span></div>; }
function PageHead({ kicker, title, en, copy }: { kicker: string; title: string; en: string; copy: string }) { return <div className="page-head"><div><span>{kicker}</span><h2>{title} <em>{en}</em></h2><p>{copy}</p></div></div>; }

function EchoPage({ state, conn, update, send, notify, chatAvatar }: { state: AppState; conn: Conn; update: (fn: (s: AppState) => AppState) => void; send: Send; notify: (s: string) => void; chatAvatar: string }) {
  const [text, setText] = useState("");
  const submit = () => { const value = text.trim(); if (!value) return; const id = newEventId(); update((s) => ({ ...s, messages: [...s.messages, { text: value, at: new Date().toISOString(), event_id: id, sync: "queued" as Sync }].slice(-80) })); send("/api/web/message", { text: value }, id); setText(""); if (conn !== "online") notify("已存本机，连接后自动发送"); };
  const remoteIds = new Set(state.remoteMessages.map((m) => m.id));
  const list = [
    ...state.remoteMessages.map((m) => ({ key: m.id, text: m.text, at: m.at, me: !m.fromCompanion, label: "Runtime" })),
    ...state.messages.filter((m) => !m.event_id || !remoteIds.has(m.event_id)).map((m, i) => ({ key: m.event_id ?? `${m.at}-${i}`, text: m.text, at: m.at, me: true, label: m.sync === "synced" ? "已发送" : "本地 · 待发送" })),
  ].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  return <section className="page active">
    <PageHead kicker="YOUR PRIVATE CONVERSATION" title="回响" en="Echo" copy="给你的回声，永远写在纸的另一面" />
    <div className="echo-person glass"><img src={chatAvatar} alt="纪临洲" /><span><b>纪临洲</b><small>{conn === "online" ? "已连接 · 消息实时同步" : conn === "syncing" ? "正在同步……" : "未连接 · 消息先存本机"}</small></span></div>
    {conn !== "online" && <div className="preview-tip">现在还没连上 Runtime。你写下的话会先排队保存在此浏览器，连上后自动送达。</div>}
    <div className="message-list">
      {list.length === 0 && <div className="message-row companion"><img className="message-avatar" src={chatAvatar} alt="" /><div className="message">音音，今天如果什么都不想做，就来坐一会儿。<time>纪临洲</time></div></div>}
      {list.map((m) => <div className={`message-row ${m.me ? "me" : "companion"}`} key={m.key}>
        {!m.me && <img className="message-avatar" src={chatAvatar} alt="" />}
        <div className={`message ${m.me ? "me" : ""}`}>{m.text}<time>{new Date(m.at).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })} · {m.label}</time></div>
      </div>)}
    </div>
    <div className="composer"><textarea maxLength={1200} placeholder="写点什么……" value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); submit(); } }} /><button onClick={submit} aria-label="发送消息">↑</button></div>
  </section>;
}

function TimelinePage({ notes, remote }: { notes: RecordItem[]; remote: RemoteRecord[] }) {
  const remoteIds = new Set(remote.map((r) => r.id));
  const items = [
    ...remote.map((r) => ({ key: `r-${r.id}`, type: r.type, body: r.body, at: r.at, label: "Runtime" })),
    ...notes.filter((n) => !n.event_id || !remoteIds.has(n.event_id)).map((n) => ({ key: `l-${n.id}`, type: n.type, body: n.body, at: n.at, label: syncLabel(n.sync) })),
  ].sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
  return <section className="page active"><PageHead kicker="OUR LITTLE HISTORY" title="你我之间" en="Timeline" copy="主动记录会在这里长成一条时间线" /><div className="timeline">{items.length ? items.map((x) => <article className="timeline-entry" key={x.key}><b>音音 · {x.type}</b><p>{x.body}</p><time>{new Date(x.at).toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })} · {x.label}</time></article>) : <div className="preview-tip">还没有记录。去首页留第一条吧。</div>}</div></section>;
}
function CalendarPage({ month, setMonth, addRecord, send, notify, savedText }: { month: Date; setMonth: (d: Date) => void; addRecord: (r: RecordItem, b?: ("notes"|"life"|"journal")[]) => void; send: Send; notify: (s:string)=>void; savedText: (w: string) => string }) {
  const [food, setFood] = useState(""); const y=month.getFullYear(), m=month.getMonth(); const first=new Date(y,m,1); const start=new Date(first); start.setDate(first.getDate()-((first.getDay()+6)%7)); const today=keyDate(new Date());
  const record = (type:string, body:string, payload: Record<string, unknown>) => { const id = newEventId(); const at = new Date().toISOString(); addRecord({id,event_id:id,sync:"queued",type,at,body}, ["notes","journal"]); send("/api/web/journal", { ...payload, type, body, date: keyDate(new Date()), at }, id); notify(savedText(type === "饮水" ? "喝水" : body)); };
  return <section className="page active"><PageHead kicker="TIME & MEMORY" title="共历" en="Calendar" copy="把生活与身体的节奏留在这里" /><div className="calendar-box glass"><div className="calendar-top"><button onClick={() => setMonth(new Date(y,m-1,1))}>‹</button><b>{y} / {String(m+1).padStart(2,"0")}</b><button onClick={() => setMonth(new Date(y,m+1,1))}>›</button></div><div className="week-row">{["一","二","三","四","五","六","日"].map(d=><span key={d}>{d}</span>)}</div><div className="calendar-grid">{Array.from({length:42},(_,i)=>{const d=new Date(start);d.setDate(start.getDate()+i);const k=keyDate(d);return <button key={k} className={`${d.getMonth()!==m?"off ":""}${k===today?"today":""}`}>{d.getDate()}</button>})}</div></div><div className="journal-box glass"><div className="section-head compact"><b>生活与身体</b><span>MANUAL JOURNAL</span></div><p className="hint">这里只记你亲手确认的事实。</p><div className="journal-row"><button onClick={()=>record("共历","感觉快来了",{kind:"period",event:"expected"})}>感觉快来了</button><button onClick={()=>record("共历","生理期实际开始",{kind:"period",event:"start"})}>今天开始</button><button onClick={()=>record("共历","生理期实际结束",{kind:"period",event:"end"})}>今天结束</button></div><div className="journal-row">{[100,200,300].map(n=><button key={n} onClick={()=>record("饮水",`喝水 ${n} ml`,{kind:"water",amount_ml:n})}>+{n} ml</button>)}</div><textarea maxLength={300} placeholder="今天吃了什么" value={food} onChange={e=>setFood(e.target.value)} /><div className="journal-row">{["早餐","午餐","晚餐","零食"].map(meal=><button key={meal} onClick={()=>{const value=food.trim();if(!value){notify("先写下吃了什么");return}record(meal,value,{kind:"meal",meal,text:value});setFood("")}}>{meal}</button>)}</div></div></section>;
}
function keyDate(d: Date) { return [d.getFullYear(),String(d.getMonth()+1).padStart(2,"0"),String(d.getDate()).padStart(2,"0")].join("-"); }

function MorePage({ state, update, setWelcome, banner, reconnect }: { state: AppState; update:(fn:(s:AppState)=>AppState)=>void; setWelcome:(v:boolean)=>void; banner: Banner; reconnect: () => void }) {
 const [key, setKey] = useState(state.webToken);
 const [url, setUrl] = useState(state.runtimeUrl);
 const [installPrompt, setInstallPrompt] = useState<InstallPromptEvent | null>(() => deferredInstallPrompt);
 const [installState, setInstallState] = useState(() => window.matchMedia("(display-mode: standalone)").matches ? "已作为 App 打开" : "浏览器模式");
 const [pushState, setPushState] = useState(() => typeof Notification !== "undefined" && Notification.permission === "granted" ? "检查中" : "未开启");
 const [pushNote, setPushNote] = useState("");

 useEffect(() => {
   const syncReady = () => {
     setInstallPrompt(deferredInstallPrompt);
     if (deferredInstallPrompt) setInstallState("可以安装");
   };
   const onInstalled = () => {
     setInstallPrompt(null);
     setInstallState("已安装");
   };
   syncReady();
   window.addEventListener("pwa-install-ready", syncReady);
   window.addEventListener("pwa-installed", onInstalled);
   return () => {
     window.removeEventListener("pwa-install-ready", syncReady);
     window.removeEventListener("pwa-installed", onInstalled);
   };
 }, []);

 useEffect(() => {
   if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
     setPushState("此浏览器不支持");
     return;
   }
   navigator.serviceWorker.ready
     .then(reg => reg.pushManager.getSubscription())
     .then(sub => setPushState(sub ? "已开启" : (Notification.permission === "denied" ? "权限被拒绝" : "未开启")))
     .catch(() => setPushState("未开启"));
 }, []);

 const currentCfg = (): RuntimeConfig | null =>
   state.webToken.trim() ? { baseUrl: state.runtimeUrl || DEFAULT_RUNTIME_URL, token: state.webToken.trim() } : null;

 const install = async () => {
   if (window.matchMedia("(display-mode: standalone)").matches) {
     setInstallState("已作为 App 打开");
     return;
   }
   const promptEvent = installPrompt || deferredInstallPrompt;
   if (!promptEvent) {
     setPushNote("浏览器还没有判定为可安装应用。请刷新一次；如果仍只看到「添加到主屏幕」，告诉我浏览器名称，我继续查安装资格。");
     return;
   }
   await promptEvent.prompt();
   const choice = await promptEvent.userChoice;
   setInstallState(choice.outcome === "accepted" ? "正在安装" : "暂未安装");
   if (choice.outcome === "accepted") setInstallPrompt(null);
 };

 const enablePush = async () => {
   const c = currentCfg();
   if (!c) { setPushNote("先连接 Runtime，再开启通知。"); return; }
   if (!("serviceWorker" in navigator) || !("PushManager" in window) || typeof Notification === "undefined") {
     setPushState("此浏览器不支持"); return;
   }
   const permission = await Notification.requestPermission();
   if (permission !== "granted") {
     setPushState("权限被拒绝");
     setPushNote("需要在系统或浏览器设置里允许「世界之间」通知。");
     return;
   }
   try {
     setPushState("正在订阅");
     const reg = await navigator.serviceWorker.ready;
     const keyInfo = await runtime.pushPublicKey(c);
     if (!keyInfo.configured || !keyInfo.public_key) throw new Error("push_not_configured");
     let sub = await reg.pushManager.getSubscription();
     if (!sub) {
       sub = await reg.pushManager.subscribe({
         userVisibleOnly: true,
         applicationServerKey: vapidKeyBytes(keyInfo.public_key),
       });
     }
     await runtime.pushSubscribe(c, sub.toJSON());
     setPushState("已开启");
     setPushNote("系统推送已绑定到这台设备。");
   } catch (err) {
     setPushState("开启失败");
     setPushNote(err instanceof Error ? err.message : "订阅失败");
   }
 };

 const disablePush = async () => {
   const c = currentCfg();
   try {
     const reg = await navigator.serviceWorker.ready;
     const sub = await reg.pushManager.getSubscription();
     if (sub && c) await runtime.pushUnsubscribe(c, sub.endpoint);
     if (sub) await sub.unsubscribe();
     setPushState("未开启");
     setPushNote("这台设备已停止接收 Web Push。");
   } catch {
     setPushNote("关闭通知失败，请稍后重试。");
   }
 };

 const testPush = async () => {
   const c = currentCfg();
   if (!c) { setPushNote("先连接 Runtime。"); return; }
   try {
     const result = await runtime.pushTest(c);
     setPushNote(result.queued ? "测试推送已发出。把网页切到后台看看通知栏。" : "还没有可用的推送订阅，请先开启通知。");
   } catch {
     setPushNote("测试推送发送失败。");
   }
 };

 const label = banner.conn === "online" ? "ONLINE · 已连接" : banner.conn === "syncing" ? "SYNCING · 同步中" : banner.configured ? `LOCAL · ${banner.lastError || "连不上"}` : "LOCAL · 未填写钥匙";
 return <section className="page active">
   <PageHead kicker="OUR LITTLE ROOMS" title="更多" en="The Rooms" copy="一扇扇门，通向我们的小世界" />
   <div className="glass settings-card"><b>✦ 风格衣橱 · Theme Wardrobe</b><small>两套风格，一个世界。换装不会清掉记录。</small><div className="theme-grid"><button className={state.theme==="mist"?"active":""} onClick={()=>update(s=>({...s,theme:"mist"}))}><i className="swatch mist"/><b>冰雾玻璃</b><small>Mist & Glass</small></button><button className={state.theme==="gothic"?"active":""} onClick={()=>update(s=>({...s,theme:"gothic"}))}><i className="swatch gothic"/><b>暗夜童话</b><small>Dark Fairytale</small></button></div></div>
   <div className="glass settings-card"><b>网页与 Runtime</b><p className="hint">钥匙只保存在这个浏览器里。留空就保持本地模式，记录会排队等待同步。</p><div className="field"><label>私人连接钥匙</label><input type="password" autoComplete="off" placeholder="留空则仅本地" value={key} onChange={e=>setKey(e.target.value)} /></div><div className="field"><label>Runtime 地址</label><input type="url" value={url} onChange={e=>setUrl(e.target.value)} /></div><div className="sheet-actions"><button className="secondary" onClick={()=>{setKey("");update(s=>({...s,webToken:""}))}}>清除钥匙</button><button className="primary" onClick={()=>{update(s=>({...s,webToken:key.trim(),runtimeUrl:url.trim()||DEFAULT_RUNTIME_URL}));reconnect()}}>保存并连接</button></div><div className="runtime-line"><span>连接状态</span><b>{label}</b></div><div className="runtime-line"><span>待同步记录</span><b>{banner.pending} 条</b></div><div className="runtime-line"><span>Android 主控制</span><b>未暴露给网页</b></div></div>
   {ANDROID_SHELL ? <div className="glass settings-card">
     <b>世界之间 · Android</b>
     <small>当前由原生 Android 外壳承载。网页负责界面，原生层继续负责后台、悬浮、定位、截图与系统通知。</small>
     <div className="runtime-line"><span>前台界面</span><b>TWA · 正式站</b></div>
     <div className="runtime-line"><span>后台能力</span><b>原生服务保留</b></div>
     <div className="sheet-actions">
       <button className="secondary" onClick={()=>{window.location.href="jlz://native/settings"}}>原生后台设置</button>
       <button className="primary" onClick={()=>{window.location.href="jlz://native/permissions"}}>权限检查</button>
     </div>
     <button className="setting-button" onClick={()=>{window.location.href="jlz://native/diagnostics"}}>打开原生诊断 <span>›</span></button>
   </div> : <div className="glass settings-card">
     <b>世界之间 · PWA</b>
     <small>安装后会像独立 App 一样从桌面打开；系统通知由 Service Worker 接收。</small>
     <div className="runtime-line"><span>安装状态</span><b>{installState}</b></div>
     <div className="runtime-line"><span>系统通知</span><b>{pushState}</b></div>
     <div className="sheet-actions"><button className="secondary" onClick={install}>安装到桌面</button><button className="primary" onClick={enablePush}>开启通知</button></div>
     <div className="sheet-actions"><button className="secondary" onClick={disablePush}>关闭通知</button><button className="secondary" onClick={testPush}>测试推送</button></div>
     {pushNote ? <p className="hint">{pushNote}</p> : null}
   </div>}
   <button className="setting-button" onClick={()=>setWelcome(true)}>重新打开邀请函 <span>›</span></button>
   <button className="setting-button" onClick={()=>{update(s=>({...defaults,theme:s.theme,runtimeUrl:s.runtimeUrl,webToken:s.webToken}));}}>清空本地历史（保留连接） <span>›</span></button>
   <button className="setting-button danger" onClick={()=>{if(window.confirm("确定清空这个浏览器里的本地记录和钥匙吗？未同步的记录会丢失。")){localStorage.removeItem(STORE);window.location.reload()}}}>清空本地数据 <span>›</span></button>
 </section>;
}
function StatusEditor({ close, update, send, notify, savedText }: { close:()=>void; update:(fn:(s:AppState)=>AppState)=>void; send: Send; notify:(s:string)=>void; savedText:(w:string)=>string }) {
 const [enabled,setEnabled]=useState<Record<string,boolean>>({}); const [values,setValues]=useState<Record<string,number>>({}); const [picked,setPicked]=useState<Record<string,string[]>>({}); const [text,setText]=useState("");
 const toggleChip=(group:string,value:string)=>setPicked(p=>({...p,[group]:(p[group]||[]).includes(value)?(p[group]||[]).filter(x=>x!==value):[...(p[group]||[]),value]}));
 const save=()=>{const axes:Record<string,number>={};AXES.forEach(([key])=>{if(enabled[key])axes[key]=values[key]??50});const detail=Object.fromEntries(Object.entries(picked).filter(([,v])=>v.length));const body=text.trim();if(!Object.keys(axes).length&&!Object.keys(detail).length&&!body){notify("至少留下一项实际状态");return}const id=newEventId();const at=new Date().toISOString();const rec={id,event_id:id,sync:"queued" as Sync,type:"状态灯",at,body:body||"记录了此刻状态",axes,detail};update(s=>({...s,status:rec,notes:[rec,...s.notes].slice(0,120)}));send("/api/web/status",{axes,detail,text:body||null,at},id);close();notify(savedText("状态灯"))};
 return <><p className="sheet-desc">把这一刻的你告诉我。没勾选的项目就是留空，不会拿中间值替你作答。</p><div className="status-list">{AXES.map(([key,title,left,right])=><div className={`status-card ${enabled[key]?"enabled":""}`} key={key}><div className="status-head"><div className="status-title"><b>{title}</b><small>{left} ↔ {right}</small></div><label className="tiny-check"><input type="checkbox" checked={!!enabled[key]} onChange={e=>setEnabled(v=>({...v,[key]:e.target.checked}))}/><span>{enabled[key]?"已填写":"未填"}</span></label></div><input type="range" min="0" max="100" value={values[key]??50} disabled={!enabled[key]} onChange={e=>setValues(v=>({...v,[key]:Number(e.target.value)}))}/><div className="status-scale"><span>{left}</span><b>{enabled[key]?values[key]??50:"—"}</b><span>{right}</span></div></div>)}</div><details className="detail-box"><summary>细一点记录 · 选填</summary>{Object.entries(DETAIL).map(([group,items])=><div key={group}><b className="detail-title">{group}</b><div className="chip-grid">{items.map(value=><button type="button" key={value} className={`chip ${(picked[group]||[]).includes(value)?"active":""}`} onClick={()=>toggleChip(group,value)}>{value}</button>)}</div></div>)}</details><div className="field"><label>一句原话 · 选填</label><textarea maxLength={240} placeholder="现在我想告诉你……" value={text} onChange={e=>setText(e.target.value)}/></div><div className="sheet-actions"><button className="secondary" onClick={close}>再想想</button><button className="primary" onClick={save}>点亮这盏灯 ✧</button></div></>;
}
function NoteEditor({ close, addRecord, send, notify, savedText }: { close:()=>void; addRecord:(r:RecordItem)=>void; send: Send; notify:(s:string)=>void; savedText:(w:string)=>string }) { const [text,setText]=useState("");const [reply,setReply]=useState(true);return <><p className="sheet-desc">原话会保留。连上 Runtime 时直接同步，连不上就先在本机排队。</p><div className="field"><label>今天想记下什么？</label><textarea maxLength={1200} placeholder="哪怕只是一件很小的事……" value={text} onChange={e=>setText(e.target.value)}/></div><label className="tiny-check reply-check"><input type="checkbox" checked={reply} onChange={e=>setReply(e.target.checked)}/><span>希望纪临洲回应这条</span></label><div className="sheet-actions"><button className="secondary" onClick={close}>再想想</button><button className="primary" onClick={()=>{const value=text.trim();if(!value){notify("先写点什么，音音");return}const id=newEventId();const at=new Date().toISOString();addRecord({id,event_id:id,sync:"queued",type:"随手记",at,body:value,needsReply:reply});send("/api/web/moment",{kind:"note",text:value,needs_response:reply,at},id);close();notify(savedText("随手记"))}}>留在这里</button></div></> }
function LifeEditor({ activeLife, close, update, send, notify, savedText }: { activeLife:ActiveLife;close:()=>void;update:(fn:(s:AppState)=>AppState)=>void;send: Send;notify:(s:string)=>void;savedText:(w:string)=>string }) { if(activeLife){const mins=Math.max(1,Math.round((Date.now()-activeLife.startAt)/60000));return <><p className="sheet-desc">正在记录：<b>{activeLife.action}</b> · 已约 {mins} 分钟</p><div className="sheet-actions"><button className="secondary" onClick={close}>继续</button><button className="primary" onClick={()=>{const id=newEventId();const endAt=Date.now();const rec={id,event_id:id,sync:"queued" as Sync,type:"此刻我在",at:new Date(endAt).toISOString(),body:`${activeLife.action} · 结束`,session:activeLife.session,startAt:activeLife.startAt,endAt};update(s=>({...s,activeLife:null,life:[rec,...s.life],notes:[rec,...s.notes]}));send("/api/web/life/action",{phase:"end",action:activeLife.action,session_id:activeLife.session,started_at:new Date(activeLife.startAt).toISOString(),ended_at:new Date(endAt).toISOString(),duration_ms:endAt-activeLife.startAt},id);close();notify(savedText("这一段"))}}>结束这一段</button></div></> } return <><p className="sheet-desc">把小猫现在在做什么告诉我。计时动作会保留开始和结束。</p>{Object.entries(GROUPS).map(([group,items])=><div className="life-group" key={group}><span>{group}</span><div className="life-grid">{items.map(([action,timed])=><button className="life-btn" key={action} onClick={()=>{const id=newEventId();const t=Date.now();const session=`life-${t}`;const rec={id,event_id:id,sync:"queued" as Sync,type:"此刻我在",at:new Date(t).toISOString(),body:timed?`${action} · 开始`:action};update(s=>({...s,activeLife:timed?{action,session,startAt:t}:null,life:[rec,...s.life],notes:[rec,...s.notes]}));send("/api/web/life/action",timed?{phase:"start",action,group,session_id:session,started_at:new Date(t).toISOString()}:{phase:"instant",action,group,at:new Date(t).toISOString()},id);close();notify(timed?`开始记录「${action}」`:savedText(`「${action}」`))}}>{action}<small>{timed?"开始计时":"立即记录"}</small></button>)}</div></div>)}</> }