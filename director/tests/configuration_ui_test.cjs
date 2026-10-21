// DOM/state checks only: no browser, server, dependencies, or production data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Element {
 constructor(tag='div') { this.tagName=tag; this.children=[]; this.attributes={}; this.dataset={}; this.listeners={}; this.value=''; this.checked=false; this.disabled=false; this.readOnly=false; this.hidden=false; this._text=''; this.className=''; }
 append(...children) { this.children.push(...children); }
 replaceChildren(...children) { this.children=children; this._text=''; }
 set textContent(value) { this._text=String(value); this.children=[]; }
 get textContent() { return this._text+this.children.map(child=>child.textContent??'').join(' '); }
 set innerHTML(value) { throw Error('Unsafe HTML insertion'); }
 setAttribute(name,value) { this.attributes[name]=String(value); }
 removeAttribute(name) { delete this.attributes[name]; }
 addEventListener(name,fn) { this.listeners[name]=fn; }
 focus() { this.focused=true; }
}
const rev='a'.repeat(64), nextRev='b'.repeat(64);
function fixture() { return {schema:1,state:'ready',nonce:'operator-nonce',server:{revision:rev,worldEpoch:'world-one',campaignId:'campaign',sections:[
 {id:'defense',label:'Оборона',fields:[
  {key:'sight',label:'Радиус <script>attack()</script>',type:'integer',value:32,policy:'hot',help:'Зрение через блоки',min:1,max:128,step:1},
  {key:'enabled',label:'Включено',type:'boolean',value:true,policy:'hot',help:'Действует для новых атак'},
  {key:'mode',label:'Режим',type:'string',value:'calm',policy:'hot',help:'Выберите режим',choices:['calm','siege']},
  {key:'slots',label:'Слоты',type:'integer',value:9,policy:'restart',help:'Размер хранилища',min:1,max:54,step:1}]},
 {id:'worldgen',label:'Мир',fields:[{key:'biomes',label:'Биомы',type:'json',value:{ash:1},policy:'restart',help:'Новые чанки'}]}
 ]}}; }
const clone=value=>JSON.parse(JSON.stringify(value));
const turn=async()=>{ for(let i=0;i<16;i++) await Promise.resolve(); };
async function harness() {
 const ids=new Map(['configuration','config-edit','config-apply','config-restart','config-state','config-result','config-fields','config-pending','config-restart-notice'].map(id=>[id,new Element()]));
 ids.get('configuration').hidden=false;
 const calls=[], timers=[]; let current=fixture(), handler=null;
 const document={getElementById:id=>ids.get(id),createElement:tag=>new Element(tag)};
 const context=vm.createContext({document,window:{},crypto:{randomUUID:()=> '00000000-0000-4000-8000-000000000007'},AbortController,
  setTimeout:()=>1,clearTimeout:()=>{},setInterval:fn=>{timers.push(fn);},console,
  fetch:async(url,options={})=>{calls.push({url,options}); if(handler)return handler(url,options); return {ok:true,status:200,json:async()=>clone(current)};}});
 const filename=path.join(__dirname,'../dead_earth_director/admin_web/configuration.js');
 if(fs.existsSync(filename))vm.runInContext(fs.readFileSync(filename,'utf8'),context);
 assert.ok(context.window.DeadEarthConfiguration,'The configuration form must expose its live controls');
 const api=context.window.DeadEarthConfiguration;
 await turn();
 const all=()=>{const result=[];const visit=e=>{if(!e)return;result.push(e);e.children.forEach(visit);};visit(ids.get('config-fields'));return result;};
 const input=(section,key)=>all().find(e=>e.dataset.section===section&&e.dataset.key===key);
 const change=(section,key,value)=>{const e=input(section,key);assert.ok(e,`Missing ${section}.${key}`); if(e.type==='checkbox')e.checked=value;else e.value=String(value); (e.listeners.input||e.listeners.change)();return e;};
 const respond=(payload,status=200)=>({ok:status>=200&&status<300,status,json:async()=>clone(payload)});
 return {api,ids,calls,timers,input,change,all,respond,get current(){return current;},set current(value){current=value;},set handler(value){handler=value;}};
}

async function run() {
 // Catches presenting invented live defaults, unsafe text, or enabling stale controls.
 const h=await harness();
 assert.equal(h.input('defense','sight').readOnly,true);
 assert.equal(h.input('defense','enabled').disabled,true);
 assert.equal(h.ids.get('config-apply').disabled,true);
 assert.match(h.ids.get('config-fields').textContent,/<script>attack\(\)<\/script>/);
 assert.match(h.ids.get('config-fields').textContent,/Горячие настройки/);
 assert.match(h.ids.get('config-fields').textContent,/Требуют перезапуска/);
 h.current.server.sections[0].fields[0].max=96;
 h.current.server.sections[0].fields[0].help='Свежая серверная подсказка';
 await h.api.edit();
 assert.equal(h.input('defense','sight').readOnly,false);
 assert.equal(h.input('defense','sight').attributes.max,'96');
 assert.equal(h.input('defense','sight').attributes.step,'1');
 assert.match(h.ids.get('config-fields').textContent,/Свежая серверная подсказка/);
 assert.match(h.input('defense','sight').attributes['aria-describedby'],/help/);
 h.change('defense','sight',48);h.change('defense','slots',18);
 h.api.render();await h.api.refresh();
 assert.equal(h.input('defense','sight').value,'48','Telemetry must preserve dirty values');
 assert.equal(h.input('defense','slots').value,'18');
 h.handler=async(url,options)=>options.method==='POST'?h.respond({schema:1,state:'completed',request_id:'00000000-0000-4000-8000-000000000007',result:{schema:1,accepted:true,state:'applied',requestId:'00000000-0000-4000-8000-000000000007',revision:nextRev,errors:[]}}):h.respond({...clone(h.current),server:{...clone(h.current.server),revision:nextRev,sections:[{...clone(h.current.server.sections[0]),fields:h.current.server.sections[0].fields.map(f=>f.key==='sight'?{...f,value:48}:f)},h.current.server.sections[1]]}});
 await h.api.apply('hot');
 const post=h.calls.find(c=>c.options.method==='POST');
 assert.equal(post.options.headers['X-Dead-Earth-Operator'],'operator-nonce');
 assert.deepEqual(JSON.parse(post.options.body),{request_id:'00000000-0000-4000-8000-000000000007',world_epoch:'world-one',revision:rev,mode:'hot',changes:{defense:{sight:48}}});
 assert.equal(h.input('defense','slots').value,'18','Hot apply must retain restart edits');
 assert.equal(h.ids.get('config-restart').disabled,false);
 assert.equal(h.ids.get('config-apply').disabled,true);

 // Catches stringifying booleans or enum values in the typed apply payload.
 const typed=await harness();
 typed.current.server.sections[0].fields.push({key:'level',label:'Уровень',type:'integer',value:2,policy:'hot',help:'Разрешённые уровни',choices:[2,4]});
 typed.current.server.sections[0].fields.push({key:'weights',label:'Веса',type:'json',value:{ash:1},policy:'hot',help:'Готовые наборы',choices:[{value:{ash:1},label:'Обычный'},{value:{ash:2},label:'Усиленный'}]});
 await typed.api.edit();
 assert.equal(typed.input('defense','level').value,'0','Numeric enum displays its actual live value');
 typed.change('defense','enabled',false);typed.change('defense','mode','1');typed.change('defense','level','1');typed.change('defense','weights','1');
 typed.handler=async(url,options)=>options.method==='POST'?typed.respond({schema:1,state:'completed',request_id:'00000000-0000-4000-8000-000000000007',result:{schema:1,requestId:'00000000-0000-4000-8000-000000000007',revision:nextRev,state:'applied',accepted:true,errors:[]}}):typed.respond(typed.current);
 await typed.api.apply('hot');
 assert.equal(typed.calls.filter(c=>c.options.method==='POST').length,1,'Typed choices must be submitted rather than rejected as raw JSON text');
 assert.deepEqual(JSON.parse(typed.calls.find(c=>c.options.method==='POST').options.body).changes,{defense:{enabled:false,mode:'siege',level:4,weights:{ash:2}}});

 // Catches invalid JSON being sent or a validation response erasing raw edits.
 const invalid=await harness();await invalid.api.edit();
 invalid.change('worldgen','biomes','{bad json');
 await invalid.api.apply('restart');
 assert.equal(invalid.calls.filter(c=>c.options.method==='POST').length,0);
 assert.match(invalid.ids.get('config-fields').textContent,/JSON/);
 assert.equal(invalid.input('worldgen','biomes').value,'{bad json');
 invalid.change('worldgen','biomes','{"ash":2}');invalid.change('defense','sight',50);
 invalid.handler=async(url,options)=>options.method==='POST'?invalid.respond({schema:1,state:'completed',request_id:'00000000-0000-4000-8000-000000000007',result:{schema:1,requestId:'00000000-0000-4000-8000-000000000007',revision:rev,accepted:false,state:'rejected',errors:[{section:'defense',key:'sight',code:'range',message:'Не более 40'}]}}):invalid.respond(invalid.current);
 await invalid.api.apply('restart');
 const rejected=JSON.parse(invalid.calls.find(c=>c.options.method==='POST').options.body);
 assert.deepEqual(rejected.changes,{defense:{sight:50},worldgen:{biomes:{ash:2}}});
 assert.match(invalid.ids.get('config-fields').textContent,/Не более 40/);
 assert.equal(invalid.input('defense','sight').value,'50');
 assert.equal(invalid.input('defense','sight').attributes['aria-invalid'],'true');
 invalid.handler=async()=>{throw Error('offline');};await invalid.api.refresh();
 assert.equal(invalid.input('defense','sight').value,'50','Read failure preserves edits');
 assert.equal(invalid.ids.get('config-apply').disabled,true);
 assert.match(invalid.ids.get('config-state').textContent,/недоступ|устарел/i);

 // Catches native section/global failures disappearing because they have no matching input.
 for(const [error,detail] of [
  [{section:'farming',key:'',code:'invalid_section',message:'Минимальный свет не выше максимального.'},/Фермерство.*Минимальный свет не выше максимального/],
  [{section:'',key:'',code:'busy',message:''},/Сервер.*busy/],
  [{section:'',key:'',code:'file_too_large',message:''},/Сервер.*file_too_large/],
  [{section:'',key:'',code:'persistence_failed',message:'Сохранение <script>failure()</script> не удалось.'},/Сервер.*Сохранение <script>failure\(\)<\/script> не удалось/]
 ]){
  const failed=await harness();failed.current.server.sections.push({id:'farming',label:'Фермерство',fields:[]});
  await failed.api.edit();failed.change('defense','sight',45);failed.change('defense','slots',18);
  failed.handler=async(url,options)=>options.method==='POST'?failed.respond({schema:1,state:'completed',request_id:'00000000-0000-4000-8000-000000000007',result:{schema:1,requestId:'00000000-0000-4000-8000-000000000007',revision:rev,accepted:false,state:'rejected',errors:[error]}}):failed.respond(failed.current);
  await failed.api.apply('hot');
  assert.match(failed.ids.get('config-result').textContent,detail,'Unmatched native '+error.code+' must remain visible');
  assert.doesNotMatch(failed.ids.get('config-result').textContent,/отмеченные поля/,'Do not direct the operator to nonexistent input errors');
  assert.equal(failed.input('defense','sight').value,'45');
  assert.equal(failed.input('defense','slots').value,'18');
  await failed.api.refresh();
  assert.match(failed.ids.get('config-result').textContent,detail,'Telemetry must retain the failure detail');
 }

 // Catches a definitive pre-dispatch rejection being trapped as an uncertain request forever.
 for(const status of [400,403,409]){
  const rejected=await harness();await rejected.api.edit();rejected.change('defense','sight',44);rejected.change('defense','slots',18);
  rejected.handler=async(url,options)=>options.method==='POST'?rejected.respond({error:status===403?'operator_session_required':'invalid_configuration_request'},status):rejected.respond(rejected.current);
  await rejected.api.apply('hot');
  assert.equal(rejected.calls.filter(c=>c.url.startsWith('/api/config/receipt?')).length,0,'Definitive HTTP '+status+' rejection has no dispatched receipt');
  assert.equal(rejected.input('defense','sight').value,'44');
  assert.equal(rejected.input('defense','slots').value,'18');
  rejected.current.nonce='new-operator-nonce';await rejected.api.edit();
  assert.equal(rejected.ids.get('config-apply').disabled,false,'Fresh Edit recovers a definitive HTTP '+status+' rejection');
 }

 // Catches duplicate submission and dangerous timeout retry, including a missed receipt binding.
 const uncertain=await harness();await uncertain.api.edit();uncertain.change('defense','sight',64);
 let resolvePost;const hold=new Promise(resolve=>{resolvePost=resolve;});
 uncertain.handler=async(url,options)=>{if(options.method==='POST'){await hold;throw Error('lost reply');} if(url.startsWith('/api/config/receipt?'))return uncertain.respond({schema:1,state:'uncertain',request_id:'00000000-0000-4000-8000-000000000007'});return uncertain.respond(uncertain.current);};
 const first=uncertain.api.apply('hot');await uncertain.api.apply('hot');
 resolvePost();await first;await uncertain.api.apply('hot');
 assert.equal(uncertain.calls.filter(c=>c.options.method==='POST').length,1);
 assert.ok(uncertain.calls.some(c=>c.url==='/api/config/receipt?request_id=00000000-0000-4000-8000-000000000007'));
 assert.equal(uncertain.ids.get('config-apply').disabled,true);
 assert.match(uncertain.ids.get('config-result').textContent,/неизвест|подтвержд/i);
 const completed={schema:1,state:'completed',request_id:'00000000-0000-4000-8000-000000000007',result:{schema:1,requestId:'00000000-0000-4000-8000-000000000007',accepted:true,state:'applied',revision:nextRev,errors:[]}};
 uncertain.handler=async url=>url.startsWith('/api/config/receipt?')?uncertain.respond({...completed,request_id:undefined}):uncertain.respond(uncertain.current);
 await uncertain.api.refresh();
 assert.equal(uncertain.ids.get('config-edit').disabled,true,'A receipt without its UUID cannot release an uncertain operation');
 await uncertain.api.edit();
 assert.equal(uncertain.ids.get('config-apply').disabled,true,'A receipt without its UUID cannot release an uncertain operation');
 uncertain.handler=async url=>url.startsWith('/api/config/receipt?')?uncertain.respond(completed):uncertain.respond({...clone(uncertain.current),server:{...clone(uncertain.current.server),revision:nextRev}});
 await uncertain.api.refresh();
 assert.match(uncertain.ids.get('config-result').textContent,/применены/);

 // Catches a restart claiming success before the matching supervisor readiness acknowledgement.
 const restart=await harness();await restart.api.edit();restart.change('defense','slots',27);
 let restartState={schema:1,state:'queued',requestId:'00000000-0000-4000-8000-000000000007',worldEpoch:'world-one',revision:nextRev};
 restart.handler=async(url,options)=>options.method==='POST'?restart.respond({schema:1,state:'completed',request_id:'00000000-0000-4000-8000-000000000007',result:{schema:1,accepted:true,state:'restart_pending',revision:nextRev,requestId:'00000000-0000-4000-8000-000000000007',errors:[]}}):url==='/api/config/restart'?restart.respond(restartState):restart.respond(restart.current);
 await restart.api.apply('restart');
 assert.match(restart.ids.get('config-restart-notice').textContent,/сохран|отключ/i);
 assert.match(restart.ids.get('config-result').textContent,/Сохранено|перезапуск/i);
 assert.equal(restart.ids.get('config-apply').disabled,true);
 restartState={...restartState,state:'ready',requestId:'different-request'};
 await restart.api.refresh();
 assert.doesNotMatch(restart.ids.get('config-result').textContent,/Сервер готов/);
 restartState={...restartState,requestId:'00000000-0000-4000-8000-000000000007'};
 await restart.api.refresh();
 assert.match(restart.ids.get('config-result').textContent,/Сервер готов/);

 // Catches a durable restart_pending receipt overriding a failed startup that restored old config.
 const rollback=await harness();await rollback.api.edit();rollback.change('defense','slots',27);
 rollback.handler=async(url,options)=>options.method==='POST'||url.startsWith('/api/config/receipt?')?rollback.respond({schema:1,state:'completed',request_id:'00000000-0000-4000-8000-000000000007',result:{schema:1,accepted:true,state:'restart_pending',revision:nextRev,requestId:'00000000-0000-4000-8000-000000000007',errors:[]}}):url==='/api/config/restart'?rollback.respond({schema:1,state:'failed',requestId:'00000000-0000-4000-8000-000000000007',worldEpoch:'world-one',revision:nextRev,rollback:true,code:'rolled_back'}):rollback.respond(rollback.current);
 await rollback.api.apply('restart');await rollback.api.refresh();await rollback.api.refresh();
 assert.match(rollback.ids.get('config-result').textContent,/возврат к предыдущим настройкам/);
 assert.doesNotMatch(rollback.ids.get('config-result').textContent,/Сервер готов/);
 assert.equal(rollback.calls.filter(c=>c.url.startsWith('/api/config/receipt?')).length,0,'Restart lifecycle remains authoritative after the native receipt');
 assert.equal(rollback.input('defense','slots').value,'27');

 // Catches malformed snapshots or revision changes silently rebasing stale dirty forms.
 const stale=await harness();await stale.api.edit();stale.change('defense','sight',40);
 stale.current.server.revision=nextRev;await stale.api.refresh();
 assert.equal(stale.ids.get('config-apply').disabled,true);
 assert.equal(stale.input('defense','sight').value,'40');
 await stale.api.edit();assert.equal(stale.input('defense','sight').value,'40');
 assert.equal(stale.ids.get('config-apply').disabled,false);
 const offline=await harness();offline.current={schema:1,state:'offline',nonce:'x',server:null};await offline.api.refresh();
 assert.equal(offline.ids.get('config-apply').disabled,true);
 const malformed=await harness();malformed.current.server.sections[0].fields[0].value='not an integer';await malformed.api.refresh();
 assert.equal(malformed.ids.get('config-apply').disabled,true);
 console.log('Configuration UI DOM/state checks passed: typed form, fresh help, dirty preservation, validation, hot/restart isolation, single send, uncertain receipt, bound restart readiness, offline/stale safety.');
}
run().catch(error=>{console.error(error);process.exitCode=1;});
