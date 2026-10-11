import { useEffect, useState } from "react";

type Event = { stable_instance_key: string; title: string; start_ms: number; end_ms: number; calendar_id: number; provider_sync_id: string };
export function NativeCalendarPanel({ date }: { date: string }) {
  const [events, setEvents] = useState<Event[]>([]);
  const [reason, setReason] = useState("尚未读取本机选定日历");
  useEffect(() => {
    const read = () => {
      const fn = window.WorldBetweenStudy?.calendarRange;
      if (!fn) { setReason("Android 原生 App 内可查看已授权日历"); return; }
      try {
        const from = new Date(`${date}T00:00:00+08:00`).getTime();
        const result = JSON.parse(fn.call(window.WorldBetweenStudy, from, from + 86_400_000));
        const rows = Array.isArray(result.events) ? result.events : [];
        const unique = new Map<string, Event>();
        for (const e of rows) if (typeof e.stable_instance_key === "string") unique.set(e.stable_instance_key, e);
        setEvents([...unique.values()]);
        setReason(result.ok ? `已读取 ${date} 北京时间全天；本机选定日历，Google 日程需系统账户同步。${result.possibly_truncated ? '仅显示前60项。' : ''}` : "请在作息入口选择日历并授权读取。");
      } catch { setReason("日历读取失败，未显示旧缓存为实时事件"); setEvents([]); }
    };
    read(); const timer = window.setInterval(read, 60_000);
    return () => window.clearInterval(timer);
  }, [date]);
  const begin = new Date(`${date}T00:00:00+08:00`).getTime();
  const day = events.filter(e => e.start_ms < begin + 86_400_000 && e.end_ms > begin);
  return <article className="room-entry glass"><h3>日程</h3><p className="hint">{reason}</p>{day.map(e => <div key={e.stable_instance_key}><p>{e.title}</p><small>{new Date(e.start_ms).toLocaleTimeString("zh-CN", { hour:"2-digit", minute:"2-digit", timeZone:"Asia/Shanghai" })} · 日历 {e.calendar_id}</small></div>)}{!day.length && <p className="hint">这一天没有已加载的日程。</p>}<button className="text-action" onClick={() => { window.location.href = "jlz://native/modes"; }}>选择日历</button></article>;
}
