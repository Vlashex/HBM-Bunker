const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
class Element{
 constructor(){this.listeners={};this.attributes={};this.textContent='';this.disabled=false;}
 addEventListener(event,fn){this.listeners[event]=fn;}
 setAttribute(key,value){this.attributes[key]=String(value);}
 set innerHTML(_){throw Error('Unsafe HTML insertion');}
}
const file=path.join(__dirname,'../dead_earth_director/admin_web/ai-mode.js');
assert(fs.existsSync(file),'AI mode controls missing');
const html=fs.readFileSync(path.join(__dirname,'../dead_earth_director/admin_web/index.html'),'utf8');
const ids=new Map([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Element()]));
const get=id=>{assert(ids.has(id),'Missing AI mode control '+id);return ids.get(id);};
let fixture={desired_mode:'dual',revision:null,active_mode:'dual',state:'applied',critic_memory:'loaded',nonce:'ai-session',dual_available:true,host_run_mode:'live'};
let fail=false,held=null,reply=null;const calls=[];
const context=vm.createContext({document:{getElementById:get},window:{},console,AbortController,
 setInterval:()=>{},setTimeout:()=>1,clearTimeout:()=>{},crypto:{randomUUID:()=> '00000000-0000-4000-8000-000000000010'},
 fetch:async(url,options={})=>{calls.push({url,options});if(options.method==='POST'){
  if(held)await held;if(fail)throw Error('lost');return {ok:!reply?.error,status:reply?.error?409:200,json:async()=>reply||fixture};
 }return {ok:true,status:200,json:async()=>fixture};}});
vm.runInContext(fs.readFileSync(file,'utf8'),context);
async function test(){
 const api=context.window.DeadEarthAiMode;assert(api);await new Promise(setImmediate);await api.refresh();
 assert.equal(get('ai-dual').attributes['aria-pressed'],'true');
 assert.match(get('ai-mode-memory').textContent,/загружена/);
 let release;held=new Promise(resolve=>release=resolve);
 reply={...fixture,desired_mode:'single',state:'pending',critic_memory:'released',revision:'00000000-0000-4000-8000-000000000010'};
 const first=api.switchMode('single');await api.switchMode('single');
 assert.equal(get('ai-single').disabled,true);release();await first;held=null;
 const posts=calls.filter(c=>c.options.method==='POST');assert.equal(posts.length,1);
 assert.deepEqual(JSON.parse(posts[0].options.body),{mode:'single',request_id:'00000000-0000-4000-8000-000000000010',expected_revision:null});
 assert.equal(posts[0].options.headers['X-Dead-Earth-Operator'],'ai-session');
 assert.match(get('ai-mode-state').textContent,/Переключение|Ожида/);
 assert.match(get('ai-mode-memory').textContent,/выгружена/);
 fixture={...reply,state:'applied',active_mode:'single'};await api.refresh();
 assert.match(get('ai-mode-state').textContent,/одна модель|1 модель/);
 assert.equal(get('ai-single').disabled,true);
 fixture={...fixture,critic_memory:'unknown'};await api.refresh();
 assert.match(get('ai-mode-memory').textContent,/не подтверждена/);
 assert.equal(get('ai-single').disabled,false,'Single must allow retrying an unconfirmed unload');
 fixture={...fixture,dual_available:false};await api.refresh();assert.equal(get('ai-dual').disabled,true);
 fixture={...fixture,dual_available:true};await api.refresh();fail=true;
 const count=calls.filter(c=>c.options.method==='POST').length;await api.switchMode('dual');
 assert.equal(calls.filter(c=>c.options.method==='POST').length,count+1,'Unknown POST must not retry automatically');
 assert.match(get('ai-mode-result').textContent,/неизвестен|мог/);
 console.log('AI mode DOM: confirmed/pending/unload status, guarded single send, unknown outcome and unavailable critic passed.');
}
test().catch(error=>{console.error(error);process.exitCode=1;});
