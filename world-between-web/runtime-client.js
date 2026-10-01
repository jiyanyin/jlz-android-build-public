/* World Between low-privilege Runtime bridge. Never store Android/MCP credentials here. */
(() => {
  "use strict";
  const SPACE_ID = "world-between-primary";
  const CFG = "jlz-world-between-runtime-v2";
  const OUTBOX = "jlz-world-between-outbox-v2";
  const CLIENT = "jlz-world-between-client-id";
  const SEEN = "jlz-world-between-seen-messages-v2";
  const read = (key, fallback) => { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } };
  const write = (key, value) => localStorage.setItem(key, JSON.stringify(value));
  const uuid = () => crypto.randomUUID?.() || `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  let config = read(CFG, { baseUrl: "", token: "" });
  let outbox = read(OUTBOX, []);
  let lastInteractionAtMs = Date.now();
  const clientId = localStorage.getItem(CLIENT) || uuid();
  localStorage.setItem(CLIENT, clientId);

  async function request(path, body) {
    if (!config.baseUrl || !config.token) throw new Error("runtime_not_configured");
    const response = await fetch(config.baseUrl.replace(/\/$/, "") + path, {
      method: body ? "POST" : "GET",
      headers: {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "X-Web-Token": config.token
      },
      body: body ? JSON.stringify(body) : undefined
    });
    const json = await response.json().catch(() => ({}));
    if (!response.ok || json.ok === false) throw new Error(json.error || `HTTP_${response.status}`);
    return json;
  }

  async function send(path, payload) {
    const item = {
      id: payload.event_id || payload.id || uuid(),
      path,
      payload: { ...payload, space_id: SPACE_ID },
      created_at_ms: Date.now()
    };
    try {
      return await request(path, item.payload);
    } catch (error) {
      if (!outbox.some(entry => entry.id === item.id)) outbox.push(item);
      write(OUTBOX, outbox.slice(-1000));
      return { ok: false, queued: true, error: String(error) };
    }
  }

  async function flush() {
    const pending = outbox;
    outbox = [];
    for (const item of pending) {
      try { await request(item.path, item.payload); }
      catch { outbox.push(item); }
    }
    write(OUTBOX, outbox.slice(-1000));
    return outbox.length;
  }

  async function presence() {
    return request("/api/web/presence", {
      space_id: SPACE_ID,
      client_id: clientId,
      visible: document.visibilityState === "visible",
      focused: document.hasFocus(),
      last_interaction_at_ms: lastInteractionAtMs
    });
  }

  let seen = new Set(read(SEEN, []));
  async function pollMessages() {
    if (document.visibilityState !== "visible") return;
    const data = await request(`/api/web/messages?space_id=${encodeURIComponent(SPACE_ID)}&limit=80`);
    for (const message of [...(data.messages || [])].reverse()) {
      if (message.role !== "companion" || seen.has(message.id)) continue;
      seen.add(message.id);
      write(SEEN, [...seen].slice(-200));
      window.dispatchEvent(new CustomEvent("jlz:web-message", { detail: message }));
    }
  }

  function configure(next) {
    config = {
      baseUrl: String(next.baseUrl || config.baseUrl || "").trim().replace(/\/$/, ""),
      token: String(next.token || config.token || "").trim()
    };
    write(CFG, config);
  }

  function touch() { lastInteractionAtMs = Date.now(); }
  addEventListener("pointerdown", touch, { passive: true });
  addEventListener("keydown", touch, { passive: true });

  window.JLZWorldBetween = {
    configure,
    config: () => ({ ...config, token: config.token ? "configured" : "" }),
    configured: () => Boolean(config.baseUrl && config.token),
    pendingCount: () => outbox.length,
    request,
    send,
    flush,
    presence,
    pollMessages,
    health: () => request("/api/web/health"),
    status: payload => send("/api/web/status", payload),
    moment: payload => send("/api/web/moment", payload),
    lifeAction: payload => send("/api/web/life/action", payload),
    journal: payload => send("/api/web/journal", payload),
    message: (text, id = uuid()) => send("/api/web/message", { id, text }),
    state: () => request(`/api/web/state?space_id=${encodeURIComponent(SPACE_ID)}`)
  };

  addEventListener("online", () => { flush().then(pollMessages).catch(() => {}); });
  addEventListener("focus", () => { touch(); presence().catch(() => {}); pollMessages().catch(() => {}); });
  addEventListener("blur", () => presence().catch(() => {}));
  document.addEventListener("visibilitychange", () => presence().catch(() => {}));
  setInterval(() => { presence().catch(() => {}); pollMessages().catch(() => {}); }, 30_000);
  if (config.baseUrl && config.token) {
    flush().then(() => presence()).then(pollMessages).catch(() => {});
  }
})();
