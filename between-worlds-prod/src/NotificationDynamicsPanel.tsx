import { useState } from 'react';
import { runtime, type RuntimeConfig, type NotificationDynamic } from './lib/runtime';

/** On-demand safe history; mounting this panel never scans the device or clicks a notice. */
export function NotificationDynamicsPanel({cfg}:{cfg:RuntimeConfig|null}) {
  const [device,setDevice]=useState('android-phone-native-n0');
  const [rows,setRows]=useState<NotificationDynamic[]>([]);
  const [status,setStatus]=useState('');
  const [busy,setBusy]=useState(false);
  async function load() {
    if (!cfg) {setStatus("请先连接世界之间。");return;}
    setBusy(true);
    try {
      const result=await runtime.notifications(cfg,device);
      setRows(result.notifications);
      setStatus(result.listener_connected === false ? '通知监听未连接。下面是已收录的历史。' :
        '有限历史；没有记录不代表没有通知。');
    } catch {setRows([]);setStatus('暂时连不上。稍后再读取。');}
    finally {setBusy(false);}
  }
  return <article className="room-entry glass"><h3>通知动态</h3>
    <div className="journal-row"><button disabled={busy} onClick={()=>{setDevice('android-phone-native-n0');setRows([]);setStatus('');}} aria-pressed={device==='android-phone-native-n0'}>手机</button>
      <button disabled={busy} onClick={()=>{setDevice('android-tablet-native-n0');setRows([]);setStatus('');}} aria-pressed={device==='android-tablet-native-n0'}>平板</button>
      <button disabled={busy} onClick={()=>void load()}>{busy?'读取中…':'读取动态'}</button></div>
    {status && <p className="hint">{status}</p>}
    {rows.map(row=><details key={row.id}><summary>{row.metadata.notification_lifecycle==='removed'?'已撤回 · ':row.metadata.notification_lifecycle==='updated'?'已更新 · ':''}{row.title || row.package_name}</summary>
      <small>{row.package_name} · {new Date(row.created_at).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}</small>
      <p>{row.subtitle || '未记录正文'}</p></details>)}
  </article>;
}
