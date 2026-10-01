(()=>{'use strict';
const $=s=>document.querySelector(s), $$=s=>Array.from(document.querySelectorAll(s));
const STORE='world-between-web-v1';const defaults={theme:'mist',notes:[],messages:[],status:null,life:[],journal:[],activeLife:null};
let state;try{state={...defaults,...JSON.parse(localStorage.getItem(STORE)||'{}')}}catch{state={...defaults}};
state.messages=(state.messages||[]).map(message=>({...message,role:message.role||'user'}));
const save=()=>{try{localStorage.setItem(STORE,JSON.stringify(state))}catch{}};
const esc=s=>String(s??'').replace(/[&<>"']/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]));
const runtime=()=>window.JLZWorldBetween;
const eventId=prefix=>prefix+'-'+Date.now()+'-'+Math.random().toString(16).slice(2);
function runtimeUi(mode,detail=''){
 const connected=mode==='online';
 $('#runtimeBadge').textContent=connected?'ONLINE':mode==='syncing'?'SYNC':'LOCAL';
 $('#runtimeBannerTitle').textContent=connected?'Runtime 已连接 · 世界记录同步中':'本机可记录 · Runtime 待连接';
 $('#runtimeBannerDetail').textContent=detail||(connected?'网页消息、状态灯和生活记录已接入同一世界。':'未连接时会留在浏览器；连接后自动同步。');
 $('#runtimeConnectionState').textContent=connected?'已连接':mode==='syncing'?'正在连接':'待连接';
 $('#echoConnection').textContent=connected?'Runtime 在线 · 网页前台可收消息':'未连接 Runtime · 消息先保存在本机';
 $('#runtimePendingCount').textContent=String(runtime()?.pendingCount?.()||0);
}
async function syncRecord(promise,label){
 const result=await promise;
 $('#runtimePendingCount').textContent=String(runtime()?.pendingCount?.()||0);
 if(result?.queued){runtimeUi('local','网络暂不可用，记录已进入本机耐久队列。');toast(label+'已留在本机，恢复后同步')}
 else{runtimeUi('online');toast(label+'已同步')}
 return result;
}
let toastTimer;
function toast(msg){const e=$('#toast');e.textContent=msg;e.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>e.classList.remove('show'),2200)}
function setTheme(t){state.theme=t==='gothic'?'gothic':'mist';document.body.dataset.theme=state.theme;document.querySelector('meta[name="theme-color"]').content=state.theme==='gothic'?'#21171c':'#eef0fc';save();$$('[data-theme-option]').forEach(b=>b.classList.toggle('active',b.dataset.themeOption===state.theme))}
function flipTheme(){setTheme(state.theme==='mist'?'gothic':'mist')}
setTheme(state.theme);
$('#quickTheme').onclick=flipTheme;$('#welcomeTheme').onclick=flipTheme;
$$('[data-theme-option]').forEach(b=>b.onclick=()=>setTheme(b.dataset.themeOption));
$('#enterWorld').onclick=()=>$('#entrance').classList.add('hidden');
$('#reopenWelcome').onclick=()=>$('#entrance').classList.remove('hidden');
$$('[data-toast]').forEach(b=>b.onclick=()=>toast(b.dataset.toast));

function clock(){const d=new Date();$('#clockTop').textContent=d.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false});const h=d.getHours();$('#greeting').textContent=(h<5?'夜深了':h<11?'早上好':h<14?'中午好':h<18?'下午好':'晚上好')+'，音音。';$('#dateLabel').textContent=new Intl.DateTimeFormat('en-US',{weekday:'long',month:'long',day:'numeric'}).format(d).toUpperCase()}
clock();setInterval(clock,30000);

let current='home';
function tab(name){if(!$('#page-'+name))return;current=name;$$('.page').forEach(p=>p.classList.toggle('active',p.id==='page-'+name));$$('.dock [data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===name));window.scrollTo({top:0,behavior:'smooth'});if(name==='timeline')renderTimeline();if(name==='echo')renderMessages();if(name==='calendar')renderCalendar()}
$$('[data-tab]').forEach(b=>b.onclick=()=>tab(b.dataset.tab));

function openSheet(title,html){$('#sheetTitle').textContent=title;$('#sheetBody').innerHTML=html;$('#scrim').classList.add('show');$('#sheet').classList.add('open');return $('#sheetBody')}
function closeSheet(){$('#scrim').classList.remove('show');$('#sheet').classList.remove('open')}
$('#scrim').onclick=closeSheet;$('#closeSheet').onclick=closeSheet;
const AXES=[
 ['self_presence','自我在场感','像在自动运行','我就是我'],
 ['emotion_access','情绪可感度','摸不到情绪','感受得很清楚'],
 ['emotional_vividness','情感鲜活度','麻木、冷淡','鲜活、有温度'],
 ['agitation','宁静—烦躁','宁静、松弛','烦躁、紧绷']
];
const DETAIL={
 '我能辨认出的情绪':['开心','安心','兴奋','被打动','满足','委屈','失落','难过','孤独','生气','烦躁','担心','害怕','茫然','麻木','没感觉'],
 '我表现出来的样子':['说话平静','正在微笑','正在哭','不想说话','机械应答','表现烦躁','正常交流但内心没感觉'],
 '我想怎样和你亲近':['抱紧我','主动亲亲我','让我感受到偏爱','热烈一点','强势一点但要宠我','你也向我撒娇','主动逗逗我','安静拥着我'],
 '身体的具体信号':['疼痛','紧绷','发沉','麻木','乏力','呼吸变化','流泪','发热','发冷'],
 '想听我怎样回应':['热烈直白一点','温柔地宠着我','强势一点但疼我','主动向我讨亲近','多逗逗我','先别分析','冷静简短就好','现在先不用回复']
};
function openStatus(){
 const cards=AXES.map(([k,t,l,r])=>'<div class="status-card" data-card="'+k+'"><div class="status-head"><div class="status-title"><b>'+t+'</b><small>'+l+' ↔ '+r+'</small></div><label class="tiny-check"><input type="checkbox" data-check="'+k+'"><span data-mark="'+k+'">未填</span></label></div><input type="range" min="0" max="100" value="50" data-range="'+k+'" disabled><div class="status-scale"><span>'+l+'</span><b data-score="'+k+'">—</b><span>'+r+'</span></div></div>').join('');
 const details=Object.entries(DETAIL).map(([title,vals])=>'<div><b style="font-size:10px">'+title+'</b><div class="chip-grid">'+vals.map(v=>'<button type="button" class="chip" data-chip="'+esc(title)+'" data-val="'+esc(v)+'">'+esc(v)+'</button>').join('')+'</div></div>').join('');
 const body=openSheet('状态灯','<p class="sheet-desc">把这一刻的你告诉我。没勾选的项目就是留空，不会拿中间值替你作答。</p><div class="status-list">'+cards+'</div><details class="detail-box"><summary>细一点记录 · 选填</summary>'+details+'</details><div class="field"><label>一句原话 · 选填</label><textarea id="statusText" maxlength="240" placeholder="现在我想告诉你……"></textarea></div><div class="sheet-actions"><button class="secondary" id="cancelStatus">再想想</button><button class="primary" id="saveStatus">点亮这盏灯 ✧</button></div>');
 const picked={};
 body.querySelectorAll('[data-check]').forEach(c=>{const k=c.dataset.check,r=body.querySelector('[data-range="'+k+'"]'),card=body.querySelector('[data-card="'+k+'"]'),score=body.querySelector('[data-score="'+k+'"]'),mark=body.querySelector('[data-mark="'+k+'"]');const paint=()=>{r.disabled=!c.checked;card.classList.toggle('enabled',c.checked);score.textContent=c.checked?r.value:'—';mark.textContent=c.checked?'已填写':'未填'};c.onchange=paint;r.oninput=()=>{if(c.checked)score.textContent=r.value};paint()});
 body.querySelectorAll('[data-chip]').forEach(b=>b.onclick=()=>{const k=b.dataset.chip;picked[k]??=new Set();if(picked[k].has(b.dataset.val)){picked[k].delete(b.dataset.val);b.classList.remove('active')}else{picked[k].add(b.dataset.val);b.classList.add('active')}});
 body.querySelector('#cancelStatus').onclick=closeSheet;
 body.querySelector('#saveStatus').onclick=()=>{
  const axes={};AXES.forEach(([k])=>{const c=body.querySelector('[data-check="'+k+'"]');if(c.checked)axes[k]=Number(body.querySelector('[data-range="'+k+'"]').value)});
  const detail={};Object.entries(picked).forEach(([k,v])=>{if(v.size)detail[k]=[...v]});
  const text=body.querySelector('#statusText').value.trim();if(!Object.keys(axes).length&&!Object.keys(detail).length&&!text){toast('至少留下一项实际状态');return}
  const id=eventId('web-status'),rec={id,type:'状态灯',at:new Date().toISOString(),body:text||'记录了此刻状态',axes,detail};state.status=rec;state.notes.unshift(rec);state.notes=state.notes.slice(0,120);save();closeSheet();renderTimeline();
  syncRecord(runtime().status({event_id:id,state:text||'已填写状态灯',detail:text,dimensions:axes,emotions:detail['我能辨认出的情绪']||[],body_signals:detail['身体的具体信号']||[],need:detail['我想怎样和你亲近']||[],response_style:detail['想听我怎样回应']||[],updated_at_ms:Date.now()}),'状态灯');
 };
}
function openNote(){
 const body=openSheet('随手记','<p class="sheet-desc">原话会先存本机；连接 Runtime 后进入同一个世界的 Inbox。</p><div class="field"><label>今天想记下什么？</label><textarea id="noteText" maxlength="1200" placeholder="哪怕只是一件很小的事……"></textarea></div><label class="tiny-check" style="justify-content:flex-start;margin:8px 0"><input type="checkbox" id="needReply" checked><span>希望纪临洲回应这条</span></label><div class="sheet-actions"><button class="secondary" id="cancelNote">再想想</button><button class="primary" id="saveNote">留在这里</button></div>');
 body.querySelector('#cancelNote').onclick=closeSheet;body.querySelector('#saveNote').onclick=()=>{const text=body.querySelector('#noteText').value.trim();if(!text){toast('先写点什么，音音');return}const id=eventId('web-note'),needsReply=body.querySelector('#needReply').checked,rec={id,type:'随手记',at:new Date().toISOString(),body:text,needsReply};state.notes.unshift(rec);state.notes=state.notes.slice(0,120);save();closeSheet();renderTimeline();syncRecord(runtime().moment({event_id:id,text,kind:'note',needs_response:needsReply,created_at_ms:Date.now()}),'随手记')};
}
const GROUPS={
 '常用':[['吃饭',1],['喝水',0],['走路',1],['学习',1],['发呆',1],['上厕所',0],['休息',1],['起床',0],['回家',0]],
 '身体作息':[['准备睡觉',0],['睡醒',0],['洗漱',1],['洗澡',1],['化妆',1],['换衣服',0],['身体不舒服',0],['躺一会儿',1]],
 '工作学习':[['工作',1],['刷题',1],['复盘',1],['写申论',1],['读书',1],['背书',1]],
 '放松玩耍':[['听歌',1],['看视频',1],['玩游戏',1],['刷手机',1],['聊天',1],['看小说',1]]
};
function finishLife(){if(!state.activeLife)return;const a=state.activeLife,id=eventId('web-life-end'),endAt=Date.now(),rec={id,type:'此刻我在',at:new Date().toISOString(),body:a.action+' · 结束',session:a.session,startAt:a.startAt,endAt};state.life.unshift(rec);state.notes.unshift(rec);state.activeLife=null;save();closeSheet();renderTimeline();syncRecord(runtime().lifeAction({event_id:id,action:a.action,note:'end',session_id:a.session,start_at_ms:a.startAt,end_at_ms:endAt,created_at_ms:endAt}),'这一段')}
function openLife(){
 if(state.activeLife){const mins=Math.max(1,Math.round((Date.now()-state.activeLife.startAt)/60000));const body=openSheet('此刻我在','<p class="sheet-desc">正在记录：<b>'+esc(state.activeLife.action)+'</b> · 已约 '+mins+' 分钟</p><div class="sheet-actions"><button class="secondary" id="keepLife">继续</button><button class="primary" id="finishLife">结束这一段</button></div>');body.querySelector('#keepLife').onclick=closeSheet;body.querySelector('#finishLife').onclick=finishLife;return}
 const html=Object.entries(GROUPS).map(([g,arr])=>'<div class="life-group"><span>'+g+'</span><div class="life-grid">'+arr.map(([name,timed])=>'<button class="life-btn" data-life="'+esc(name)+'" data-timed="'+timed+'">'+esc(name)+'<small>'+(timed?'开始计时':'立即记录')+'</small></button>').join('')+'</div></div>').join('');
 const body=openSheet('此刻我在','<p class="sheet-desc">把小猫现在在做什么告诉我。计时动作会保留开始和结束。</p>'+html);
 body.querySelectorAll('[data-life]').forEach(b=>b.onclick=()=>{const action=b.dataset.life,id=eventId('web-life');if(b.dataset.timed==='1'){state.activeLife={action,session:'life-'+Date.now(),startAt:Date.now()};const rec={id,type:'此刻我在',at:new Date().toISOString(),body:action+' · 开始',session:state.activeLife.session};state.life.unshift(rec);state.notes.unshift(rec);save();closeSheet();syncRecord(runtime().lifeAction({event_id:id,action,note:'start',session_id:state.activeLife.session,start_at_ms:state.activeLife.startAt,created_at_ms:state.activeLife.startAt}),'「'+action+'」')}else{const rec={id,type:'此刻我在',at:new Date().toISOString(),body:action};state.life.unshift(rec);state.notes.unshift(rec);save();closeSheet();renderTimeline();syncRecord(runtime().lifeAction({event_id:id,action,created_at_ms:Date.now()}),'「'+action+'」')}});
}
$$('[data-open]').forEach(b=>b.onclick=()=>({status:openStatus,note:openNote,life:openLife}[b.dataset.open]||(()=>{}))());

function renderTimeline(){const items=[...state.notes].sort((a,b)=>Date.parse(b.at)-Date.parse(a.at));$('#timelineList').innerHTML=items.length?items.map(x=>'<article class="timeline-entry"><b>音音 · '+esc(x.type)+'</b><p>'+esc(x.body)+'</p><time>'+new Date(x.at).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})+' · 本地</time></article>').join(''):'<div class="preview-tip">还没有本地记录。去首页留第一条吧。</div>'}
renderTimeline();

function renderMessages(){$('#messageList').innerHTML=state.messages.map(x=>'<div class="message '+(x.role==='user'?'me':'')+'">'+esc(x.text)+'<time>'+new Date(x.at).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})+' · '+(x.synced?'已同步':'本机')+'</time></div>').join('')||'<div class="preview-tip">还没有消息。网页连接后，你写的话会进入同一个 Inbox。</div>'}
function sendMessage(){const e=$('#messageInput'),t=e.value.trim();if(!t)return;const id=eventId('web-message'),entry={id,text:t,role:'user',at:new Date().toISOString(),synced:false};state.messages.push(entry);state.messages=state.messages.slice(-80);save();e.value='';renderMessages();syncRecord(runtime().message(t,id),'消息').then(result=>{if(!result?.queued){entry.synced=true;save();renderMessages()}})}
$('#sendMessage').onclick=sendMessage;$('#messageInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();sendMessage()}});
renderMessages();

let month=new Date(new Date().getFullYear(),new Date().getMonth(),1);
const keyDate=d=>[d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-');
function addDays(d,n){const x=new Date(d);x.setDate(x.getDate()+n);return x}
function renderCalendar(){const y=month.getFullYear(),m=month.getMonth();$('#monthTitle').textContent=y+' / '+String(m+1).padStart(2,'0');const first=new Date(y,m,1),start=addDays(first,-((first.getDay()+6)%7)),today=keyDate(new Date());$('#calendarGrid').innerHTML=Array.from({length:42},(_,i)=>{const d=addDays(start,i),k=keyDate(d),off=d.getMonth()!==m;return '<button class="'+(off?'off ':'')+(k===today?'today':'')+'" data-day="'+k+'">'+d.getDate()+'</button>'}).join('')}
$('#prevMonth').onclick=()=>{month.setMonth(month.getMonth()-1);renderCalendar()};$('#nextMonth').onclick=()=>{month.setMonth(month.getMonth()+1);renderCalendar()};renderCalendar();
$$('[data-journal]').forEach(b=>b.onclick=()=>{const label={cycle_expected:'感觉快来了',cycle_start:'生理期实际开始',cycle_end:'生理期实际结束'}[b.dataset.journal],id=eventId('web-journal'),rec={id,type:'共历',at:new Date().toISOString(),body:label};state.journal.unshift(rec);state.notes.unshift(rec);save();syncRecord(runtime().journal({event_id:id,kind:b.dataset.journal,text:label,created_at_ms:Date.now()}),'共历')});
$$('[data-water]').forEach(b=>b.onclick=()=>{const text='喝水 '+b.dataset.water+' ml',id=eventId('web-water'),rec={id,type:'饮水',at:new Date().toISOString(),body:text};state.journal.unshift(rec);state.notes.unshift(rec);save();syncRecord(runtime().journal({event_id:id,kind:'water',text,amount_ml:Number(b.dataset.water),created_at_ms:Date.now()}),'饮水')});
$$('[data-meal]').forEach(b=>b.onclick=()=>{const t=$('#foodInput').value.trim();if(!t){toast('先写下吃了什么');return}const id=eventId('web-meal'),rec={id,type:b.dataset.meal,at:new Date().toISOString(),body:t};state.journal.unshift(rec);state.notes.unshift(rec);$('#foodInput').value='';save();syncRecord(runtime().journal({event_id:id,kind:'meal_'+b.dataset.meal,text:t,created_at_ms:Date.now()}),b.dataset.meal)});
const savedRuntime=runtime().config();
$('#runtimeBaseUrl').value=savedRuntime.baseUrl||'';
$('#runtimeBanner').onclick=()=>tab('more');
async function connectRuntime(){
 const baseUrl=$('#runtimeBaseUrl').value.trim(),token=$('#runtimeWebToken').value.trim();
 if(!baseUrl){toast('先填写 Runtime 地址');return}
 runtime().configure({baseUrl,token});runtimeUi('syncing');
 try{await runtime().health();await runtime().flush();await runtime().presence();await runtime().pollMessages();$('#runtimeWebToken').value='';runtimeUi('online');toast('世界已经接通')}
 catch(error){runtimeUi('local','连接失败：'+String(error.message||error));toast('暂时没接通，记录仍会留在本机')}
}
$('#connectRuntime').onclick=connectRuntime;

function showWebMessage(message){
 if(!message?.id||state.messages.some(item=>item.id===message.id))return;
 state.messages.push({id:message.id,text:message.text||'……',role:'companion',at:message.created_at||new Date().toISOString(),synced:true});
 state.messages=state.messages.slice(-80);save();renderMessages();
 $('#webMessageText').textContent=message.text||'……';$('#webMessagePopup').classList.add('show');
}
addEventListener('jlz:web-message',event=>showWebMessage(event.detail));
$('#closeWebMessage').onclick=()=>$('#webMessagePopup').classList.remove('show');
$('#openWebEcho').onclick=()=>{$('#webMessagePopup').classList.remove('show');tab('echo')};

runtimeUi(runtime().configured()?'syncing':'local');
if(runtime().configured())runtime().health().then(()=>runtime().flush()).then(()=>runtime().presence()).then(()=>runtime().pollMessages()).then(()=>runtimeUi('online')).catch(error=>runtimeUi('local','Runtime 暂不可用：'+String(error.message||error)));

$('#clearLocal').onclick=()=>{if(confirm('确定清空这个浏览器里的试玩记录吗？')){localStorage.removeItem(STORE);location.reload()}};
})();
