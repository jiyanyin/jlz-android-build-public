import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";

const androidShell = new URLSearchParams(window.location.search).get("shell") === "android";

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    if (androidShell) {
      // The native WebShell owns lifecycle/background behavior. A PWA service
      // worker here only risks serving stale bundles inside the Android app.
      navigator.serviceWorker.getRegistrations()
        .then(regs => Promise.all(regs.map(reg => reg.unregister())))
        .catch(() => {});
      if ("caches" in window) {
        caches.keys()
          .then(keys => Promise.all(keys.filter(key => key.startsWith("between-worlds-pwa-")).map(key => caches.delete(key))))
          .catch(() => {});
      }
      return;
    }
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  });
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
