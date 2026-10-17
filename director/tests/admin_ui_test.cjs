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
for(const match of html.matchAll(/id="([^"]+)"/g))ids.set(match[1],new Element());
ids.get('range').value='6';
const views=['overview','decisions','scenarios','zones','catalog'];
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
vm.runInContext('fixture.connection.state="offline";render()',context);
assert.match(ids.get('notice').textContent,/Свежих данных нет/);
console.log('Admin UI DOM checks passed: five screens, charts, decision detail/search, archive, catalog, offline state, literal untrusted text.');
