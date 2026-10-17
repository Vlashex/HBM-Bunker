// Pure DOM harness: does not launch a browser or use computer input.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
class Element {
 constructor(tag='div'){this.tagName=tag;this.children=[];this.listeners={};this.dataset={};this.value='';this._text='';this.disabled=false;this.hidden=false;this.attributes={};}
 append(...items){this.children.push(...items);}
 replaceChildren(...items){this.children=[...items];this._text='';}
 set textContent(value){this._text=String(value);this.children=[];}
 get textContent(){return this._text+this.children.map(c=>c.textContent||'').join(' ');}
 set innerHTML(_){throw Error('unsafe HTML');}
 setAttribute(k,v){this.attributes[k]=String(v);}
 addEventListener(name,fn){this.listeners[name]=fn;}
}
const ids=new Map();
for(const id of ['authoring','authoring-state','authoring-result','authoring-launch','authoring-library','authoring-editor','authoring-operations','authoring-receipts'])ids.set(id,new Element());
const catalog=JSON.parse(fs.readFileSync(path.join(__dirname,'../../pack/config/dead-earth/director/catalog.json'),'utf8'));
const calls=[];let fail=false;
function find(root,predicate){if(predicate(root))return root;for(const child of root.children){const result=find(child,predicate);if(result)return result;}return null;}
function get(id){if(ids.has(id))return ids.get(id);for(const root of ids.values()){const found=find(root,e=>e.id===id);if(found)return found;}return null;}
function labeled(caption){return find(ids.get('authoring-editor'),e=>e.tagName==='label'&&e.children[0]?.textContent===caption)?.children[1];}
const fixture={state:'ready',nonce:'operator-test',server:{worldEpoch:'epoch',catalogRevision:'a'.repeat(64),enabled:true,zones:[{id:'zone',value:100,complete:true}]},catalog,drafts:[],receipts:[]};
let sequence=0;
const context=vm.createContext({document:{getElementById:get,createElement:t=>new Element(t)},window:{},structuredClone,console,crypto:{randomUUID:()=> '00000000-0000-4000-8000-'+String(++sequence).padStart(12,'0')},setInterval:()=>{},setTimeout:()=>1,clearTimeout:()=>{},AbortController,
 fetch:async(url,options={})=>{calls.push({url,options});if(fail)throw Error('lost');let result=fixture;if(options.method==='POST'){const payload=JSON.parse(options.body);result={state:'completed',result:{accepted:true,code:'queued',operationId:'op'}};if(url.endsWith('/save')){const draft={event:payload.event,revision:payload.draft_revision+1};fixture.drafts=[draft];result={state:'saved',draft};}if(url.endsWith('/validate'))result.result.code='validated';}return {ok:true,json:async()=>result};}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../dead_earth_director/admin_web/authoring.js'),'utf8'),context);
async function test(){
 const api=context.window.DeadEarthAuthoring;assert(api,'authoring API missing');await api.refresh();api.newDraft();
 assert.match(ids.get('authoring-editor').textContent,/Шаги сценария/);
 const first=api.inspect().event;assert(first.id.startsWith('custom_'));assert.equal(first.nodes.at(-1).actions[0].type,'finish');
 const trigger=labeled('Когда'),role=labeled('Где');trigger.value='burn';trigger.listeners.change();role.value='flank';role.listeners.change();assert.equal(api.inspect().event.nodes[0].branches[0].when,'burn:flank','editing role must preserve newly selected trigger');
 const template=find(ids.get('authoring-library'),e=>e.tagName==='select');template.value=catalog.events[1].id;template.listeners.change();
 const cancelInput=find(ids.get('authoring-operations'),e=>e.tagName==='input');cancelInput.value='operation-to-cancel';cancelInput.listeners.input();
 api.render();assert.equal(find(ids.get('authoring-library'),e=>e.tagName==='select').value,catalog.events[1].id,'polling must preserve selected template');assert.equal(find(ids.get('authoring-operations'),e=>e.tagName==='input').value,'operation-to-cancel','polling must preserve cancellation UUID');
 api.clone(catalog.events[0].id);assert.equal(api.inspect().event.id,first.id,'unsaved edits must not be discarded implicitly');api.discard();api.clone(catalog.events[0].id);const cloned=api.inspect().event;assert.notEqual(cloned.id,catalog.events[0].id);cloned.title='external mutation';assert.notEqual(api.inspect().event.title,cloned.title);
 const before=api.inspect().event;api.addNode();const after=api.inspect().event;assert.equal(after.nodes.length,before.nodes.length+1);
 assert.equal(catalog.events[0].nodes.length,before.nodes.length,'cloning must not edit built-in events');
 await api.refresh();assert.equal(api.inspect().event.nodes.length,after.nodes.length,'polling must preserve unsaved edits');
 assert.equal(get('ae-save').disabled,false);assert.equal(get('ae-publish').disabled,true,'dirty draft must not publish');
 await get('ae-save').listeners.click();assert.equal(api.inspect().dirty,false);assert.equal(api.inspect().revision,1);assert.equal(get('ae-save').disabled,true);assert.equal(get('ae-publish').disabled,true,'server validation required');
 await get('ae-validate').listeners.click();assert.equal(get('ae-publish').disabled,false,'validated saved draft can publish');
 await api.perform('preview',{event_id:catalog.events[0].id,zone_id:'zone',world_epoch:'epoch',catalog_revision:'a'.repeat(64)});
 const posted=calls.find(c=>c.options.method==='POST');assert.equal(posted.options.headers['X-Dead-Earth-Operator'],'operator-test');assert(!('command' in JSON.parse(posted.options.body)));
 fail=true;await api.perform('run',{event_id:catalog.events[0].id,zone_id:'zone',world_epoch:'epoch',catalog_revision:'a'.repeat(64)});
 assert.match(ids.get('authoring-result').textContent,/могло выполниться/);
 assert.equal(calls.filter(c=>c.url.endsWith('/run')).length,1,'uncertain request must not be retried');
 console.log('Authoring DOM checks passed: editor, clone isolation, graph insertion, trigger editing, draft persistence, save/validate/publish gate, operator nonce, uncertain single send.');
}
test().catch(error=>{console.error(error);process.exitCode=1;});
