// DOM-only regression checks: no browser, server, or third-party dependencies.
const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm'), path=require('node:path');
class Element {
 constructor(tag='div'){this.tagName=tag;this.children=[];this.dataset={};this.attributes={};this.value='';this.open=false;this.className='';this.hidden=false;this.listeners={};this._text='';this.classList={toggle:()=>{}};}
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
const ids=new Map(), html=fs.readFileSync(path.join(__dirname,'../dead_earth_director/admin_web/index.html'),'utf8');
for(const m of html.matchAll(/id="([^"]+)"/g))ids.set(m[1],new Element());
ids.get('range').value='6';
const views=['overview','decisions','scenarios','zones','catalog','console','evolution'];
const nav=views.map(v=>{const e=new Element('button');e.dataset.view=v;return e;});
const document={getElementById:id=>{assert(ids.has(id),'Missing element '+id);return ids.get(id);},createElement:t=>new Element(t),createElementNS:(ns,t)=>new Element(t),createTextNode:t=>({textContent:String(t)}),querySelector:()=>new Element(),querySelectorAll:s=>s==='.view'?views.map(v=>{ids.get(v).id=v;return ids.get(v);}):nav};
const operation={operationId:'op-done',eventId:'event',zoneId:'zone',createdTick:100,expiresTick:500,endedTick:200,state:'COMPLETED',coverage:'partial',participants:2,participantsTruncated:true,damage:{hp:7,absorbedHp:1,deaths:1,ownedAttributedHp:5,spatialAttributedHp:2,points:15},observations:{signalEmissions:1,tracePlacements:1,traceInteractions:1,nearEntries:2,approaches:1,trapEntries:0,retreats:1},proxy:{distraction:'observed',deception:'insufficient_data',score:1,reason:'<img src=x onerror=attack()>'},timeline:[{tick:120,kind:'damage',role:'lure',detail:'<script>timeline()</script>',count:1,hp:7,attribution:'owned'}],droppedTimeline:3};
const fixture={product:'dead-earth-admin-v1',connection:{state:'online',scope:'campaign:epoch'},model:{},scope:'campaign:epoch',scopes:[],generated_at:2000,sampled_at:2000,samples:[],snapshot:{activeTicks:220,zones:[],observations:{}},queue:{operations:[],history:[]},archive:{scenarios:[{operationId:'op-done',eventId:'event',outcome:'repelled',tick:200}],observations:[],analytics:[operation]},analytics:{schema:1,status:'ready',sampleTicks:20,operations:[operation,operation],totals:{operations:1,hp:7,absorbedHp:1,deaths:1,points:15}},catalog:{events:[],rules:{}},decisions:{status:'ok',items:[{goal_id:'goal',choice:{event_id:'event',source:'model',metrics:{ai_mode:'dual',elapsed_ms:300,revision_count:0,fallback_reason:'critic_timeout',stages:[{stage:'proposer',model:'first',status:'validated',option:0,elapsed_ms:100,reason_codes:['eligible']},{stage:'critic',model:'second',status:'timeout',elapsed_ms:200,error_code:'model_timeout'}]}},report:{state:'queued',operation_id:'op-done'}}]}};
const context=vm.createContext({document,Node:Element,Option:class extends Element{constructor(t,v){super('option');this.textContent=t;this.value=v;}},history:{replaceState:()=>{}},location:{hash:''},URLSearchParams,AbortController,Intl,Date,console,fixture,window:{},fetch:async()=>({ok:true,json:async()=>fixture}),setInterval:()=>{},setTimeout:()=>1,clearTimeout:()=>{}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../dead_earth_director/admin_web/admin.js'),'utf8'),context);
vm.runInContext('data=fixture;setView("scenarios")',context);
assert.match(ids.get('analytics-status').textContent,/1 HP/);
assert.match(ids.get('analytics-summary').textContent,/7/);
assert.equal(ids.get('analytics-list').children.length,1,'Duplicate snapshots must show one operation card');
const card=ids.get('analytics-list').children[0];
assert.match(card.textContent,/7 HP/);assert.match(card.textContent,/5 с/);assert.match(card.textContent,/Частичное/);
assert.match(card.textContent,/Участники.*2.*усечён/);assert.match(card.textContent,/3.*пропущен/);
assert.match(card.textContent,/<img src=x onerror=attack\(\)>/);assert.match(card.textContent,/<script>timeline\(\)<\/script>/);
assert.match(card.textContent,/Атака отражена/,'Outcome is joined by operationId');
assert.equal(ids.get('analytics-hp-chart').children[0].tagName,'svg');
assert.equal(ids.get('analytics-deaths-chart').children[0].tagName,'svg');
card.open=true;vm.runInContext('scenarios()',context);assert.equal(ids.get('analytics-list').children[0].open,true);
vm.runInContext('setView("decisions")',context);
assert.match(ids.get('decision-list').textContent,/Два ИИ/);assert.match(ids.get('decision-list').textContent,/first/);assert.match(ids.get('decision-list').textContent,/second/);assert.match(ids.get('decision-list').textContent,/critic_timeout/);assert.match(ids.get('decision-list').textContent,/7 HP/);
vm.runInContext('fixture.analytics={status:"unavailable",operations:[],totals:{hp:null,deaths:null,points:null}};setView("scenarios")',context);
assert.match(ids.get('analytics-status').textContent,/недоступна/);assert.doesNotMatch(ids.get('analytics-summary').textContent,/0 HP/);
vm.runInContext('fixture.analytics={status:"ready",operations:[{...fixture.archive.analytics[0],damage:{hp:null,deaths:null},proxy:{},timeline:[]}],totals:{}};scenarios()',context);
assert.match(ids.get('analytics-list').textContent,/— HP/);assert.match(ids.get('analytics-list').textContent,/Недостаточно данных/);
async function lazyChecks(){
 await Promise.resolve();await Promise.resolve();
 const requests=[], op={...operation,operationId:'00000000-0000-4000-8000-000000000007',timeline:[],detailsAvailable:true,wireTruncated:true,omittedTimeline:1,lastSeq:1};
 fixture.analytics={status:'ready',operations:[op],totals:{operations:1,hp:7,deaths:1,points:15}};
 fixture.scope='00000000-0000-4000-8000-000000000001:00000000-0000-4000-8000-000000000002';
 context.fetch=async(url,options)=>{requests.push(url);return {ok:true,json:async()=>({schema:1,status:'ready',operation:{...op,detailsAvailable:false,wireTruncated:false,omittedTimeline:0,timeline:operation.timeline}})};};
 vm.runInContext('data=fixture;setView("scenarios")',context);
 assert.equal(requests.length,0,'Closed dossiers must not query the bridge');
 let current=ids.get('analytics-list').children[0];current.open=true;
 await current.listeners.toggle();
 assert.equal(requests.length,1);assert.equal(new URL('http://local'+requests[0]).searchParams.get('operation_id'),op.operationId);
 assert.equal(new URL('http://local'+requests[0]).searchParams.get('scope'),fixture.scope);
 assert.match(ids.get('analytics-list').textContent,/<script>timeline\(\)<\/script>/);
 current=ids.get('analytics-list').children[0];await current.listeners.toggle();assert.equal(requests.length,1,'Repeated toggle uses the dossier already loaded for this revision');
 const unseen='00000000-0000-4000-8000-000000000008';
 const historyLink=vm.runInContext('outcomeAnalytics({operationId:"'+unseen+'",eventId:"unseen-event",zoneId:"zone",outcome:"repelled"})',context);
 const button=historyLink.children.find(c=>c.tagName==='button');
 assert(button,'History must offer a dossier lookup even when the latest server summary omitted this operation');
 context.fetch=async url=>{requests.push(url);return {ok:true,json:async()=>({schema:1,status:'ready',operation:{...operation,operationId:unseen,wireTruncated:false}})};};
 await button.listeners.click();
 assert.equal(requests.length,2);
 assert.match(ids.get('analytics-list').textContent,/unseen-event|event/);
 assert.equal(ids.get('analytics-list').children.find(c=>c.dataset.id===unseen).open,true);
 console.log('Analytics DOM checks passed: unique cards, HP/deaths charts, literal text, coverage/truncation, history links, council stages, unavailable/missing values, scoped lazy dossier with single fetch.');
}
lazyChecks().catch(error=>{console.error(error);process.exitCode=1;});
