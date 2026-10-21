// DOM unit harness only: no browser, screen, input, or external dependencies.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
class Element {
 constructor(tag='div'){this.tagName=tag;this.children=[];this.dataset={};this.attributes={};this.value='';this.open=false;this.className='';this.listeners={};this._text='';this.hidden=false;this.classList={toggle:()=>{}};}
 append(...nodes){this.children.push(...nodes);}
 replaceChildren(...nodes){this.children=[...nodes];this._text='';}
 set textContent(v){this._text=String(v);this.children=[];}
 get textContent(){return this._text+this.children.map(c=>c.textContent??String(c)).join(' ');}
 set innerHTML(v){throw Error('Unsafe HTML insertion');}
 setAttribute(k,v){this.attributes[k]=String(v);}
 removeAttribute(k){delete this.attributes[k];}
 addEventListener(k,fn){this.listeners[k]=fn;}
 querySelectorAll(selector){const found=[];const visit=e=>{for(const c of e.children){if(c instanceof Element){if(selector==='details[open]'&&c.tagName==='details'&&c.open)found.push(c);visit(c);}}};visit(this);return found;}
}
const ids=new Map();
const html=fs.readFileSync(path.join(__dirname,'../dead_earth_director/admin_web/index.html'),'utf8');
assert.match(html,/id="console-command"/,'Operator console input is missing');
for(const match of html.matchAll(/id="([^"]+)"/g))ids.set(match[1],new Element());
ids.get('range').value='6';
const views=['overview','decisions','scenarios','zones','catalog','console','evolution','configuration'];
const nav=views.map(v=>{const e=new Element('button');e.dataset.view=v;return e;});
const note=new Element();
const document={getElementById:id=>{assert(ids.has(id),'Unknown element '+id);return ids.get(id);},
 createElement:t=>new Element(t),createElementNS:(ns,t)=>new Element(t),createTextNode:t=>({textContent:String(t)}),
 querySelector:()=>note,querySelectorAll:s=>s==='.view'?views.map(v=>{ids.get(v).id=v;return ids.get(v);}):nav};
const catalog=JSON.parse(fs.readFileSync(path.join(__dirname,'../../pack/config/dead-earth/director/catalog.json'),'utf8'));
const fixture={product:'dead-earth-admin-v1',generated_at:2000,connection:{state:'online',scope:'campaign:epoch'},model:{state:'ready',name:'test-model'},
 scopes:[{id:'campaign:epoch',at:2000}],scope:'campaign:epoch',sampled_at:2000,
 snapshot:{onlinePlayers:2,activeTicks:24000,climate:{phase:'ASH_WINTER',ticksRemaining:1200},difficulty:{pressure:1.2,parasiteHealth:1.8,parasiteDamage:1.8,forestPhaseRate:.25},zones:[{id:'zone',value:400,complete:true,resourceEmc:400,resourceCoefficient:1,armedRank:0,emptyRank:0}],observations:{history:[]}},
 samples:[{at:1000,online:true,gap_before:false,metrics:{pressure:1,parasiteEvolutionRate:1,forestPhaseRate:1,zoneValue:100,active:0,queued:0}},{at:1100,online:true,gap_before:false,metrics:{pressure:1.2,parasiteEvolutionRate:1.5,forestPhaseRate:.25,zoneValue:400,active:1,queued:1}}],
 queue:{operations:[{id:'op',eventId:catalog.events[0].id,zoneId:'zone',state:'WAITING',node:catalog.events[0].start,budget:4,spent:1,expires:25000}],history:[]},
 archive:{scenarios:[{operationId:'op-done',eventId:catalog.events[0].id,zoneId:'zone',tick:23000,outcome:'repelled',spent:3}],observations:[{kind:'DEATH',subject:'<img src=x onerror=attack()>',tick:22000}]},
 decisions:{status:'ok',unscoped:0,items:[{goal_id:'goal',request_id:'request',choice:{event_id:catalog.events[0].id,zone_id:'zone',source:'model',reason:'model_validated',metrics:{elapsed_ms:200,prompt_tokens:300,output_tokens:5},audit:{recorded_at:1500,selected_index:0,season:'ASH_WINTER',pressure:1.2,options:[{eventId:catalog.events[0].id,title:'<script>attack()</script>',weight:1.5,budget:4}]}},report:{state:'queued',operation_id:'op-done'}}]},
 catalog:{status:'ok',events:catalog.events,rules:catalog.rules,sounds:103,traces:48}};
const context=vm.createContext({document,Node:Element,Option:class extends Element{constructor(t,v){super('option');this.textContent=t;this.value=v;}},history:{replaceState:()=>{}},location:{hash:''},URLSearchParams,AbortController,Intl,Date,console,fixture,fetch:async()=>({ok:true,json:async()=>fixture}),setInterval:()=>{},setTimeout:()=>1,clearTimeout:()=>{}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../dead_earth_director/admin_web/admin.js'),'utf8'),context);
vm.runInContext('data=fixture;render()',context);
assert.match(ids.get('connection-top').textContent,/доступен/);
assert.match(ids.get('season-content').textContent,/Пепельная зима/);
assert.match(ids.get('season-content').textContent,/Развитие леса/,'Modern forest facts remain visible');
vm.runInContext('fixture.snapshot.parasiteSystem="srp";fixture.snapshot.parasitePhaseMaximum=10;fixture.snapshot.parasitePhase=10;fixture.snapshot.climate.phase="HEAT";render()',context);
assert.doesNotMatch(ids.get('season-content').textContent,/лес/i,'Native SRP overview must not advertise a forest faction');
assert.doesNotMatch(ids.get('pressure-chart').children[0].attributes['aria-label'],/лес/i,'Native SRP chart must not include forest rate');
assert.equal(vm.runInContext('tr("siege")',context),'Осада');
vm.runInContext('delete fixture.snapshot.parasiteSystem;delete fixture.snapshot.parasitePhaseMaximum;delete fixture.snapshot.parasitePhase;fixture.snapshot.climate.phase="ASH_WINTER";render()',context);
const chart=ids.get('pressure-chart').children[0];
const line=chart.children.find(c=>c.tagName==='path');
assert.match(line.attributes.d,/L/,'Reduced continuous samples must connect despite wall time spacing');
vm.runInContext('setView("decisions")',context);
assert.match(ids.get('decision-list').textContent,/<script>attack\(\)<\/script>/,'Untrusted text stays literal');
assert.match(ids.get('decision-list').textContent,/Атака отражена/);
ids.get('decision-search').value='no-result';vm.runInContext('decisions()',context);
assert.match(ids.get('decision-list').textContent,/Решений пока нет/);
vm.runInContext('setView("scenarios")',context);
assert.match(ids.get('scenario-history').textContent,/Атака отражена/);
assert.match(ids.get('observations').textContent,/<img src=x onerror=attack\(\)>/);
vm.runInContext('setView("zones");setView("catalog")',context);
assert.equal(ids.get('catalog-list').children.length,15);
assert.match(ids.get('event-detail').textContent,/Окно реакции/);
let configurationRenders=0;
context.window={DeadEarthConfiguration:{render:()=>configurationRenders++}};
vm.runInContext('setView("configuration")',context);
assert.equal(configurationRenders,1,'Configuration navigation must render the typed live editor');
assert.equal(ids.get('configuration').hidden,false);
assert.match(ids.get('page-title').textContent,/настройки/i);
vm.runInContext('setView("catalog")',context);
const configurationLink=ids.get('rules').children.find(c=>c.tagName==='a');
assert.ok(configurationLink,'Catalog parameters must lead to the typed live editor');
configurationLink.listeners.click({preventDefault:()=>{}});
assert.equal(configurationRenders,2);
vm.runInContext('fixture.connection.state="offline";render()',context);
assert.match(ids.get('notice').textContent,/Свежих данных нет/);
let evolutionRenders=0;context.window={DeadEarthEvolution:{render:()=>evolutionRenders++}};
vm.runInContext('setView("evolution")',context);
assert.equal(ids.get('evolution').hidden,false);assert.equal(evolutionRenders,1,'Evolution navigation must render its live controls');
assert.match(ids.get('page-title').textContent,/заражения/);
vm.runInContext('setView("overview")',context);
async function consoleChecks(){
 const calls=[];
 context.crypto={randomUUID:()=> '00000000-0000-4000-8000-000000000007'};
 const state={state:'ready',nonce:'test-nonce',lines:['<script>log()</script>'],source:'server-console.log',checked_at:2000,history:[{request_id:'old',command:'list',state:'completed',output:'<img>answer',at:1000}]};
 context.fetch=async(url,options={})=>{calls.push({url,options});return {ok:true,json:async()=>url==='/api/console'?state:{state:'completed'}};};
 await vm.runInContext('refreshConsole()',context);vm.runInContext('setView("console")',context);
 await Promise.resolve();await Promise.resolve();
 assert.match(ids.get('console-log').textContent,/<script>log\(\)<\/script>/);
 assert.match(ids.get('console-history').textContent,/<img>answer/);
 ids.get('console-command').value='list';
 const first=vm.runInContext('sendConsole()',context);await vm.runInContext('sendConsole()',context);await first;
 assert.equal(calls.filter(c=>c.options.method==='POST').length,1,'Double click must not send twice');
 const posted=calls.find(c=>c.options.method==='POST');
 assert.equal(posted.options.headers['X-Dead-Earth-Console'],'test-nonce');
 assert.equal(JSON.parse(posted.options.body).command,'list');
 assert.match(ids.get('console-result').textContent,/Ответ получен/);
 state.state='restart_or_server_required';await vm.runInContext('refreshConsole()',context);
 assert.equal(ids.get('console-send').disabled,true);
 assert.match(ids.get('console-state').textContent,/перезапуск/);
 state.state='ready';await vm.runInContext('refreshConsole()',context);
 ids.get('console-command').value='list';context.fetch=async()=>{throw Error('network');};
 await vm.runInContext('sendConsole()',context);
 assert.match(ids.get('console-result').textContent,/могла выполниться/);
 assert.equal(ids.get('console-send').disabled,true);
 console.log('Admin UI DOM checks passed: configuration navigation/catalog link, literal log/output, console single send, disabled state, uncertain delivery, existing charts/history/catalog.');
}
consoleChecks().catch(error=>{console.error(error);process.exitCode=1;});
