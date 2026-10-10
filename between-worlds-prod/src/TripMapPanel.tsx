import { useCallback, useEffect, useRef, useState } from "react";
import { runtime, type RuntimeConfig, type TripPoint, type TripSummary } from "./lib/runtime";

const WIDTH=340, HEIGHT=300, TILE=256;
const clamp=(n:number,min:number,max:number)=>Math.max(min,Math.min(max,n));
const projection=(p: Pick<TripPoint,"lat"|"lng">, zoom:number)=>{
  const scale=TILE*Math.pow(2,zoom);
  const lat=clamp(p.lat,-85,85)*Math.PI/180;
  return {x:(p.lng+180)/360*scale,
    y:(1-Math.log(Math.tan(lat)+1/Math.cos(lat))/Math.PI)/2*scale};
};
const localDate=(stamp:number|null)=>stamp?
  new Date(stamp).toLocaleString("zh-CN",{month:"numeric",day:"numeric",hour:"2-digit",minute:"2-digit",second:"2-digit"}):"暂无";

function TracePreview({points}:{points:TripPoint[]}) {
  const [tiles,setTiles]=useState(false);
  if(!points.length) return <div className="trip-empty">还没有收到定位点。走动后稍等片刻再刷新。</div>;
  let zoom=15;
  for(;zoom>2;zoom--) {
    const pts=points.map(p=>projection(p,zoom));
    const dx=Math.max(...pts.map(p=>p.x))-Math.min(...pts.map(p=>p.x));
    const dy=Math.max(...pts.map(p=>p.y))-Math.min(...pts.map(p=>p.y));
    if(dx<WIDTH*.72 && dy<HEIGHT*.72)break;
  }
  const projected=points.map(p=>projection(p,zoom));
  const cx=(Math.min(...projected.map(p=>p.x))+Math.max(...projected.map(p=>p.x)))/2;
  const cy=(Math.min(...projected.map(p=>p.y))+Math.max(...projected.map(p=>p.y)))/2;
  const left=cx-WIDTH/2, top=cy-HEIGHT/2;
  const trail=projected.map(p=>`${(p.x-left).toFixed(1)},${(p.y-top).toFixed(1)}`).join(" ");
  const last=projected[projected.length-1];
  const tilesList=[] as {x:number;y:number;url:string;px:number;py:number}[];
  if(tiles){
    const maxTile=2**zoom;
    for(let x=Math.floor(left/TILE);x<=Math.floor((left+WIDTH)/TILE);x++){
      for(let y=Math.floor(top/TILE);y<=Math.floor((top+HEIGHT)/TILE);y++){
        if(y<0||y>=maxTile)continue;
        tilesList.push({x,y,px:x*TILE-left,py:y*TILE-top,
          url:`https://tile.openstreetmap.org/${zoom}/${(x+maxTile)%maxTile}/${y}.png`});
      }
    }
  }
  return <div className="trip-map-root">
    <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="本次同行 GPS 轨迹线及最近采集点" preserveAspectRatio="xMidYMid meet">
      <defs><pattern id="trip-grid" width="24" height="24" patternUnits="userSpaceOnUse">
        <path d="M24 0H0V24" stroke="#8aa4c4" strokeWidth=".5" opacity=".23" fill="none"/>
      </pattern></defs>
      <rect width={WIDTH} height={HEIGHT} fill="#172b47"/>
      {!tiles && <rect width={WIDTH} height={HEIGHT} fill="url(#trip-grid)"/>}
      {tilesList.map(t=><image key={t.x+"-"+t.y} href={t.url} x={t.px} y={t.py} width={TILE} height={TILE}/>)}
      {points.length>1 && <>
        <polyline points={trail} fill="none" stroke="#10233e" strokeWidth="7" strokeLinecap="round" strokeLinejoin="round"/>
        <polyline points={trail} fill="none" stroke="#facfe8" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round"/>
      </>}
      <circle cx={last.x-left} cy={last.y-top} r="12" fill="#f8d5ed" opacity=".28"/>
      <circle cx={last.x-left} cy={last.y-top} r="6" fill="#fff3fc" stroke="#895d9c" strokeWidth="2"/>
    </svg>
    <div className="trip-map-actions">
      <span>{points.length} 个 GPS 采样点 · WGS84</span>
      <button type="button" onClick={()=>setTiles(v=>!v)}>
        {tiles?"隐藏外部底图":"加载 OpenStreetMap 底图"}
      </button>
    </div>
    {tiles && <p className="trip-map-attribution">底图 © OpenStreetMap contributors。加载瓦片会让地图服务商知晓附近地图区域；轨迹数据不上传给底图服务。</p>}
    {!tiles && <p className="trip-map-attribution">默认仅在本机绘制轨迹，无第三方底图请求。按需加载地图底图。</p>}
  </div>;
}

/** User-opened panel only: no background polling when collapsed, no GPS permissions. */
export function TripMapPanel({cfg}:{cfg:RuntimeConfig|null}) {
  const [opened,setOpened]=useState(false);
  const [session,setSession]=useState<TripSummary|null>(null);
  const [points,setPoints]=useState<TripPoint[]>([]);
  const [error,setError]=useState("");
  const [busy,setBusy]=useState(false);
  const cursor=useRef(0);
  const sessionId=useRef("");
  const seq=useRef(0);

  const refresh=useCallback(async()=>{
    if(!cfg){setError("需要先连接世界之间 Home Node，才能读取同行地图。");return;}
    const ticket=++seq.current;
    setBusy(true);
    try {
      const result=await runtime.recentTrip(cfg);
      if(ticket!==seq.current)return;
      const next=result.session||null;
      setSession(next);
      if(!next){setPoints([]);cursor.current=0;sessionId.current="";setError("");return;}
      if(next.session_id!==sessionId.current){
        sessionId.current=next.session_id;
        cursor.current=0;
        setPoints([]);
      }
      // Up to 4 pages/refresh, bounded and idempotent.
      const fetched:TripPoint[]=[];
      let pos=cursor.current;
      for(let i=0;i<4;i++){
        const page=await runtime.tripTrace(cfg,next.session_id,pos,200);
        if(ticket!==seq.current)return;
        fetched.push(...page.points);
        pos += page.points.length;
        if(page.next_cursor===null||page.points.length===0)break;
      }
      if(fetched.length) {
        setPoints(prev=>{
          const seen=new Set(prev.map(p=>p.point_id));
          return [...prev,...fetched.filter(p=>!seen.has(p.point_id))]
            .sort((a,b)=>a.observed_at_ms-b.observed_at_ms).slice(-2000);
        });
      }
      cursor.current=pos;
      setError("");
    } catch(e) {
      if(ticket===seq.current)setError("暂时无法读取轨迹；可能是设备离线、尚未部署新版 Home Node，或尚未授权连接。");
    } finally {if(ticket===seq.current)setBusy(false);}
  },[cfg?.baseUrl,cfg?.token]);

  useEffect(()=>{
    if(!opened)return;
    void refresh();
    const t=window.setInterval(()=>{void refresh()},20000);
    return ()=>{window.clearInterval(t);seq.current+=1;};
  },[opened,refresh]);

  return <section className="trip-panel">
    <button className="trip-panel-toggle" onClick={()=>setOpened(v=>!v)} aria-expanded={opened}>
      <span className="trip-panel-mark" aria-hidden="true">☾</span>
      <span><b>带着老公 · 同行地图</b><small>手机主动开启 · 仅本次 GPS 轨迹</small></span>
      <span aria-hidden="true">{opened?"⌃":"⌄"}</span>
    </button>
    {opened && <div className="trip-panel-content">
      <div className="trip-panel-actions">
        <button type="button" onClick={()=>{window.location.href="jlz://native/trip"}}>在手机打开同行开关 ↗</button>
        <button type="button" onClick={()=>void refresh()} disabled={busy}>刷新轨迹</button>
      </div>
      {!session && !error && <p className="trip-panel-help">尚未发现主动共享的行程。只有你在手机手动点击「开始同行」后才可能出现轨迹。</p>}
      {session && <div className="trip-live-meta">
        <b>{session.status==="stopped"?"同行已结束":session.fresh?"正在共享 · 采样新鲜":"同行未更新 · 位置可能已过期"}</b>
        <small>最后 GPS 采样：{localDate(session.last_observed_at_ms)} · 精度 {session.last_accuracy_m??"未知"} 米</small>
        <small>行程开始：{localDate(session.started_at_ms)} · 仅保留24小时精确点位</small>
      </div>}
      {session && <TracePreview key={session.session_id} points={points}/>}
      {error && <p className="trip-error">{error}</p>}
      <p className="trip-panel-help">GPS 是设备测量值，不代表本人身份；断网可能延迟。这里只展示你主动共享的轨迹，不自动截取高德地图，也不替代紧急求助。</p>
    </div>}
  </section>;
}
