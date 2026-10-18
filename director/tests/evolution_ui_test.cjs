// Pure DOM and fetch harness; no browser, screenshots or computer input.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
class Element {
 constructor(tag='div'){this.tagName=tag;this.children=[];this.listeners={};this.attributes={};this._text='';this.hidden=false;this.disabled=false;this.value='';}
 append(...items){this.children.push(...items);}
 replaceChildren(...items){this.children=[...items];this._text='';}
 set textContent(value){this._text=String(value);this.children=[];}
 get textContent(){return this._text+this.children.map(child=>child.textContent||'').join(' ');}
 set innerHTML(_){throw Error('Unsafe HTML insertion');}
 setAttribute(key,value){this.attributes[key]=String(value);}
 addEventListener(name,fn){this.listeners[name]=fn;}
}
const source=path.join(__dirname,'../dead_earth_director/admin_web/evolution.js');
assert(fs.existsSync(source),'Evolution UI is missing');
const html=fs.readFileSync(path.join(__dirname,'../dead_earth_director/admin_web/index.html'),'utf8');
const ids=new Map([...html.matchAll(/id="([^"]+)"/g)].map(match=>[match[1],new Element()]));
const get=id=>{assert(ids.has(id),'Missing evolution element '+id);return ids.get(id);};
const campaign='00000000-0000-4000-8000-000000000001',epoch='00000000-0000-4000-8000-000000000002';
const fixture={state:'ready',nonce:'evolution-test',server:{status:'ready',enabled:true,campaignId:campaign,worldEpoch:epoch,revision:7,configRevision:'a'.repeat(64),phase:2,name:'Паразиты <script>attack()</script>',progress:.25,points:22500.5,phaseStartPoints:18000,nextPhasePoints:36000,rate:.25,paused:false,migration:'active_campaign_recalculation',unlockedEntities:['mutationcraft:assimilated_human'],abilities:['<img>infection'],history:[{atTick:120,from:1,to:2,source:'operator',requestId:null}]}};
let failPost=false,reply={state:'completed',result:{accepted:true,code:'shifted',evolution:{...fixture.server,phase:3,revision:8}}},held=null;
const calls=[];let sequence=0;
const context=vm.createContext({document:{getElementById:get,createElement:tag=>new Element(tag)},window:{},console,Intl,AbortController,setInterval:()=>{},setTimeout:()=>1,clearTimeout:()=>{},
 crypto:{randomUUID:()=> '00000000-0000-4000-8000-'+String(++sequence).padStart(12,'0')},
 fetch:async(url,options={})=>{calls.push({url,options});if(options.method==='POST'){if(failPost)throw Error('lost');if(held)await held;return {ok:true,json:async()=>reply};}return {ok:true,json:async()=>fixture};}});
vm.runInContext(fs.readFileSync(source,'utf8'),context);
async function test(){
 const api=context.window.DeadEarthEvolution;assert(api,'Evolution API missing');await api.refresh();
 assert.match(get('evolution-name').textContent,/<script>attack\(\)<\/script>/);
 assert.match(get('evolution-abilities').textContent,/<img>infection/);
 assert.match(get('evolution-entities').textContent,/mutationcraft:assimilated_human/);
 assert.match(get('evolution-history').textContent,/1.*2/);
 assert.equal(get('evolution-progress').value,.25);
 assert.equal(get('evolution-minus').disabled,false);assert.equal(get('evolution-plus').disabled,false);
 fixture.server.phase=0;fixture.server.paused=true;fixture.server.status='paused';await api.refresh();
 assert.equal(get('evolution-minus').disabled,true);assert.equal(get('evolution-plus').disabled,false,'Manual shift remains available while automatic growth pauses');
 assert.match(get('evolution-pause').textContent,/игроков|Пауза/);
 fixture.server.phase=5;fixture.server.nextPhasePoints=null;await api.refresh();
 assert.equal(get('evolution-plus').disabled,true);assert.equal(get('evolution-minus').disabled,false);
 fixture.server.phase=2;fixture.server.paused=false;fixture.server.status='ready';fixture.server.revision=7;await api.refresh();
 let release;held=new Promise(resolve=>{release=resolve;});const first=api.shift(1);await api.shift(1);
 assert.equal(get('evolution-minus').disabled,true);assert.equal(get('evolution-plus').disabled,true);
 release();await first;held=null;
 const posts=calls.filter(call=>call.options.method==='POST');assert.equal(posts.length,1,'Double click must send one mutation');
 assert.equal(posts[0].url,'/api/evolution/shift');assert.equal(posts[0].options.headers['X-Dead-Earth-Operator'],'evolution-test');
 assert.deepEqual(JSON.parse(posts[0].options.body),{request_id:'00000000-0000-4000-8000-000000000001',campaign_id:campaign,world_epoch:epoch,revision:7,delta:1});
 assert.match(get('evolution-result').textContent,/изменена/);
 const reads=calls.filter(call=>!call.options.method).length;
 reply={state:'completed',result:{accepted:false,code:'stale_revision',evolution:{...fixture.server,revision:9}}};fixture.server.revision=9;
 await api.shift(-1);assert(calls.filter(call=>!call.options.method).length>reads,'Stale response must trigger a fresh GET');
 assert.match(get('evolution-result').textContent,/устарел|изменил|обнов/);
 const count=calls.filter(call=>call.options.method==='POST').length;failPost=true;await api.shift(1);
 assert.equal(calls.filter(call=>call.options.method==='POST').length,count+1,'Uncertain mutation must not be retried');
 assert.match(get('evolution-result').textContent,/могло|неизвестен/);
 fixture.state='ready';fixture.server={...reply.result.evolution,status:'unsupported_save',enabled:false,migration:'unavailable'};await api.refresh();
 assert.equal(get('evolution-plus').disabled,true);assert.equal(get('evolution-minus').disabled,true);
 assert.match(get('evolution-state').textContent,/Сохранение/);assert.match(get('evolution-migration').textContent,/недоступ/);
 fixture.state='unsupported';fixture.server=null;await api.refresh();assert.equal(get('evolution-plus').disabled,true);assert.equal(get('evolution-minus').disabled,true);
 assert.match(get('evolution-state').textContent,/поддерж|обнов/);
 fixture.state='offline';await api.refresh();assert.match(get('evolution-state').textContent,/недоступ|соедин/);
 console.log('Evolution DOM checks passed: phase/progress/unlocks/history, literal native text, paused/boundary controls, scoped single send, stale refresh, uncertain no retry, unsupported/offline.');
}
test().catch(error=>{console.error(error);process.exitCode=1;});
