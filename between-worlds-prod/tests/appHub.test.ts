import assert from "node:assert/strict";
import test from "node:test";
import {
  appHubCategories,
  appHubHomeItems,
  normalizeAppHubSnapshot,
} from "../src/lib/appHub.ts";

test("normalizes native App Hub payload", () => {
  const snapshot = normalizeAppHubSnapshot({
    version: "app-hub-1",
    native: true,
    pinned_order: ["com.fenbi.android.servant", "com.openai.chatgpt"],
    apps: [
      { package_name: "com.openai.chatgpt", label: "ChatGPT", category: "学习", pinned: true, hidden: false, home_rank: 1 },
      { package_name: "com.fenbi.android.servant", label: "粉笔", category: "学习", pinned: true, hidden: false, home_rank: 0 },
    ],
  });
  assert.equal(snapshot.native, true);
  assert.equal(snapshot.apps.length, 2);
  assert.deepEqual(snapshot.pinned_order, ["com.fenbi.android.servant", "com.openai.chatgpt"]);
});

test("home items obey explicit pin order and hidden state", () => {
  const snapshot = normalizeAppHubSnapshot({
    native: true,
    pinned_order: ["study", "chat", "hidden"],
    apps: [
      { package_name: "chat", label: "ChatGPT", category: "学习", pinned: true, hidden: false, home_rank: 1 },
      { package_name: "study", label: "粉笔", category: "学习", pinned: true, hidden: false, home_rank: 0 },
      { package_name: "hidden", label: "小红书", category: "娱乐与购物", pinned: true, hidden: true, home_rank: 2 },
    ],
  });
  assert.deepEqual(appHubHomeItems(snapshot, 3).map((item) => item.package_name), ["study", "chat"]);
});

test("drawer groups learning before entertainment", () => {
  const snapshot = normalizeAppHubSnapshot({
    native: true,
    apps: [
      { package_name: "xhs", label: "小红书", category: "娱乐与购物", pinned: false, hidden: false },
      { package_name: "fenbi", label: "粉笔", category: "学习", pinned: false, hidden: false },
      { package_name: "wechat", label: "微信", category: "通讯", pinned: false, hidden: false },
    ],
  });
  assert.deepEqual(appHubCategories(snapshot).map(([name]) => name), ["学习", "通讯", "娱乐与购物"]);
});
