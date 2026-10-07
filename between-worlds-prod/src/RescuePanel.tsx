import { useEffect, useState } from "react";
import { homeBridge } from "./lib/runtime";
import { validateInstructionPack, type InstructionPack } from "./lib/jlzpack";

export function RescuePanel({snapshot, apply}:{snapshot:()=>unknown; apply:(pack:InstructionPack)=>void}) {
  const [preview,setPreview]=useState<InstructionPack|null>(null);
  const [error,setError]=useState("");
  const [raw,setRaw]=useState("");
  const load=(text:string)=>{try { setPreview(validateInstructionPack(text));setError(""); } catch(e) {setPreview(null);setError(String(e));}};
  useEffect(()=>{
    const listener=(e:Event)=>load((e as CustomEvent<string>).detail);
    window.addEventListener("jlzpack-import",listener);
    return ()=>window.removeEventListener("jlzpack-import",listener);
  },[]);
  const exportPack=()=>{
    const raw=JSON.stringify(snapshot(),null,2);
    const native=homeBridge();
    if(native) native.savePack(raw);
    else {
      const url=URL.createObjectURL(new Blob([raw],{type:"application/json"}));
      const a=document.createElement("a"); a.href=url;a.download=`world-between-${new Date().toISOString().slice(0,16).replace(/[-:T]/g,"")}.jlzpack`;a.click();URL.revokeObjectURL(url);
    }
  };
  return <section className="settings-card">
    <h3>离线救生包</h3>
    <p>带走计划和生活摘要。默认不包含截图像素或连接密钥。</p>
    <button className="setting-button" onClick={exportPack}>导出给纪临洲</button>
    {homeBridge() ? <button className="setting-button" onClick={()=>homeBridge()?.openPack()}>导入纪临洲指令包</button> :
      <input type="file" accept=".jlzpack,.json" onChange={e=>{const f=e.target.files?.[0];if(f && f.size<=524288) void f.text().then(load);else setError("文件过大");}} />}
    <details><summary>粘贴指令包</summary><textarea value={raw} onChange={e=>setRaw(e.target.value)} maxLength={524288}/><button onClick={()=>load(raw)}>预览</button></details>
    {preview && <div role="dialog" aria-label="确认指令包">
      <h4>将应用 {preview.actions.length} 项变更</h4><pre style={{whiteSpace:"pre-wrap"}}>{JSON.stringify(preview.actions,null,2)}</pre>
      <p>提醒将进入 DailyPlan；留言和心声留在本地世界。Gate 更改需要本机桥接。</p>
      <button onClick={()=>{try {apply(preview);setPreview(null);setError("已确认并应用，审计已记录。");} catch(e){setError(String(e));}}}>确认应用</button>
      <button onClick={()=>setPreview(null)}>取消</button>
    </div>}
    {error && <p role="status">{error}</p>}
  </section>;
}
