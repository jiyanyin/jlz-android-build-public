import http from "node:http";
import webpush from "web-push";

const port = Number(process.env.PORT || 10000);
const bridgeKey = process.env.PUSH_BRIDGE_SECRET || "";
const publicKey = process.env.VAPID_PUBLIC_KEY || "";
const privateKey = process.env.VAPID_PRIVATE_KEY || "";
const subject = process.env.VAPID_SUBJECT || "mailto:world-between@local.invalid";

if (publicKey && privateKey) webpush.setVapidDetails(subject, publicKey, privateKey);

function sendJson(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": data.length,
    "cache-control": "no-store"
  });
  res.end(data);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      body += chunk;
      if (body.length > 131072) reject(new Error("payload_too_large"));
    });
    req.on("end", () => {
      try { resolve(body ? JSON.parse(body) : {}); }
      catch { reject(new Error("invalid_json")); }
    });
    req.on("error", reject);
  });
}

http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    return sendJson(res, 200, {
      ok: true,
      service: "between-worlds-push",
      configured: Boolean(bridgeKey && publicKey && privateKey)
    });
  }

  if (req.method !== "POST" || req.url !== "/send") {
    return sendJson(res, 404, { ok: false, error: "not_found" });
  }

  if (!bridgeKey || req.headers["x-push-bridge-secret"] !== bridgeKey) {
    return sendJson(res, 403, { ok: false, error: "forbidden" });
  }

  if (!publicKey || !privateKey) {
    return sendJson(res, 503, { ok: false, error: "vapid_not_configured" });
  }

  try {
    const body = await readJson(req);
    const subscription = body.subscription;
    const payload = body.payload || {};

    if (!subscription?.endpoint || !subscription?.keys?.p256dh || !subscription?.keys?.auth) {
      return sendJson(res, 400, { ok: false, error: "invalid_subscription" });
    }

    await webpush.sendNotification(subscription, JSON.stringify(payload), { TTL: 3600 });
    return sendJson(res, 200, { ok: true, expired: false });
  } catch (err) {
    const status = Number(err?.statusCode || 0);
    if (status === 404 || status === 410) {
      return sendJson(res, 200, { ok: false, expired: true });
    }
    console.error("push_send_error", status || err?.name || "error");
    return sendJson(res, 502, { ok: false, error: "push_send_failed", status: status || null });
  }
}).listen(port, "0.0.0.0", () => {
  console.log("between-worlds-push listening", port);
});
