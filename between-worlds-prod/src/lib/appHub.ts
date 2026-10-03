export type AppHubItem = {
  package_name: string;
  label: string;
  category: string;
  pinned: boolean;
  hidden: boolean;
  home_rank: number;
};

export type AppHubSnapshot = {
  version: string;
  native: boolean;
  pinned_order: string[];
  apps: AppHubItem[];
};

type NativeAppHubBridge = {
  version?: () => string;
  snapshot?: () => string;
  launch?: (packageName: string) => boolean;
  setPinned?: (packageName: string, pinned: boolean) => boolean;
  movePinned?: (packageName: string, direction: number) => boolean;
  setHidden?: (packageName: string, hidden: boolean) => boolean;
  openBanduread?: () => boolean;
};

declare global {
  interface Window {
    WorldBetweenAppHub?: NativeAppHubBridge;
  }
}

export const emptyAppHubSnapshot = (): AppHubSnapshot => ({
  version: "app-hub-none",
  native: false,
  pinned_order: [],
  apps: [],
});

const obj = (value: unknown): Record<string, unknown> =>
  value && typeof value === "object" ? value as Record<string, unknown> : {};
const str = (value: unknown) =>
  typeof value === "string" ? value : typeof value === "number" ? String(value) : "";
const bool = (value: unknown) =>
  value === true || value === 1 || value === "1" || value === "true";
const num = (value: unknown) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : Number.MAX_SAFE_INTEGER;
};

export const normalizeAppHubSnapshot = (payload: unknown): AppHubSnapshot => {
  const raw = obj(payload);
  const apps = (Array.isArray(raw.apps) ? raw.apps : [])
    .map((entry) => {
      const item = obj(entry);
      return {
        package_name: str(item.package_name),
        label: str(item.label),
        category: str(item.category) || "其他",
        pinned: bool(item.pinned),
        hidden: bool(item.hidden),
        home_rank: num(item.home_rank),
      };
    })
    .filter((item) => item.package_name && item.label);
  return {
    version: str(raw.version) || "app-hub-1",
    native: bool(raw.native),
    pinned_order: Array.isArray(raw.pinned_order)
      ? raw.pinned_order.map(str).filter(Boolean)
      : [],
    apps,
  };
};

export const readNativeAppHub = (): AppHubSnapshot => {
  const bridge = window.WorldBetweenAppHub;
  if (!bridge?.snapshot) return emptyAppHubSnapshot();
  try {
    return normalizeAppHubSnapshot(JSON.parse(bridge.snapshot()));
  } catch {
    return emptyAppHubSnapshot();
  }
};

export const appHubHomeItems = (snapshot: AppHubSnapshot, limit = 3): AppHubItem[] => {
  const byPackage = new Map(snapshot.apps.map((item) => [item.package_name, item]));
  const ordered = snapshot.pinned_order
    .map((pkg) => byPackage.get(pkg))
    .filter((item): item is AppHubItem => !!item && item.pinned && !item.hidden);
  const extras = snapshot.apps
    .filter((item) => item.pinned && !item.hidden && !snapshot.pinned_order.includes(item.package_name))
    .sort((a, b) => a.home_rank - b.home_rank || a.label.localeCompare(b.label, "zh-CN"));
  return [...ordered, ...extras].slice(0, Math.max(1, limit));
};

export const appHubCategories = (snapshot: AppHubSnapshot): [string, AppHubItem[]][] => {
  const order = ["学习", "通讯", "其他", "娱乐与购物", "系统"];
  const groups = new Map<string, AppHubItem[]>();
  snapshot.apps.forEach((item) => {
    const bucket = groups.get(item.category) ?? [];
    bucket.push(item);
    groups.set(item.category, bucket);
  });
  return [...groups.entries()]
    .sort(([a], [b]) => {
      const ai = order.indexOf(a), bi = order.indexOf(b);
      return (ai < 0 ? 99 : ai) - (bi < 0 ? 99 : bi) || a.localeCompare(b, "zh-CN");
    })
    .map(([category, items]) => [
      category,
      items.sort((a, b) => {
        if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
        if (a.pinned && b.pinned) return a.home_rank - b.home_rank;
        return a.label.localeCompare(b.label, "zh-CN");
      }),
    ]);
};

export const appHubActions = {
  available: () => !!window.WorldBetweenAppHub?.snapshot,
  launch: (packageName: string) => window.WorldBetweenAppHub?.launch?.(packageName) ?? false,
  setPinned: (packageName: string, pinned: boolean) =>
    window.WorldBetweenAppHub?.setPinned?.(packageName, pinned) ?? false,
  movePinned: (packageName: string, direction: number) =>
    window.WorldBetweenAppHub?.movePinned?.(packageName, direction) ?? false,
  setHidden: (packageName: string, hidden: boolean) =>
    window.WorldBetweenAppHub?.setHidden?.(packageName, hidden) ?? false,
  openBanduread: () => window.WorldBetweenAppHub?.openBanduread?.() ?? false,
};
