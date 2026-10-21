"use strict";
(()=>{
 const $=id=>document.getElementById(id);
 const types=new Set(["boolean","integer","number","string","json"]);
 let snapshot=null,availability="unknown",editing=false,reading=false,sending=false,stale=false;
 let pending=null,restarting=null,resultText="Изменения не отправлялись.";
 const drafts=new Map(),errors=new Map(),controls=new Map();
 const fieldId=(section,key)=>JSON.stringify([section,key]);
 const make=(tag,cls,text)=>{const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=String(text);return node;};
 const jsonValue=value=>value===null||typeof value==="string"||typeof value==="boolean"||typeof value==="number"&&Number.isFinite(value)||Array.isArray(value)&&value.every(jsonValue)||value&&typeof value==="object"&&Object.values(value).every(jsonValue);
 const validValue=(field,value)=>field.type==="boolean"?typeof value==="boolean":field.type==="integer"?Number.isSafeInteger(value):field.type==="number"?typeof value==="number"&&Number.isFinite(value):field.type==="string"?typeof value==="string":jsonValue(value);
 const choiceValue=choice=>choice&&typeof choice==="object"&&Object.hasOwn(choice,"value")?choice.value:choice;
 function validSnapshot(value){
  if(value?.schema!==1||value.state!=="ready"||typeof value.nonce!=="string"||!value.nonce||!/^[a-f0-9]{64}$/.test(value.server?.revision)||typeof value.server?.worldEpoch!=="string"||!value.server.worldEpoch||!Array.isArray(value.server.sections))return false;
  const sections=new Set();
  for(const section of value.server.sections){
   if(!section||typeof section.id!=="string"||!section.id||sections.has(section.id)||typeof section.label!=="string"||!Array.isArray(section.fields))return false;
   sections.add(section.id);const keys=new Set();
   for(const field of section.fields){
    if(!field||typeof field.key!=="string"||!field.key||keys.has(field.key)||typeof field.label!=="string"||typeof field.help!=="string"||!types.has(field.type)||!["hot","restart"].includes(field.policy)||!validValue(field,field.value))return false;
    keys.add(field.key);
    for(const bound of ["min","max","step"])if(field[bound]!==undefined&&(typeof field[bound]!=="number"||!Number.isFinite(field[bound])))return false;
    if(field.step!==undefined&&field.step<=0||field.min!==undefined&&field.max!==undefined&&field.min>field.max)return false;
    if(field.choices!==undefined&&(!Array.isArray(field.choices)||!field.choices.length||!field.choices.every(choice=>validValue(field,choiceValue(choice)))))return false;
   }
  }
  return true;
 }
 function entries(){return snapshot?snapshot.server.sections.flatMap(section=>section.fields.map(field=>({section,field,id:fieldId(section.id,field.key)}))):[];}
 function rawValue(field,value){return field.type==="json"?JSON.stringify(value,null,2):field.type==="boolean"?value:String(value);}
 function parse(field,raw){
  let value=raw;
  if(field.type==="json"&&!field.choices){
   try{value=JSON.parse(raw);}catch{throw Error("Некорректный JSON. Проверьте скобки, кавычки и запятые.");}
  }else if(field.type==="number"||field.type==="integer"){
   if(String(raw).trim()==="")throw Error("Введите число.");
   value=Number(raw);
  }
  if(!validValue(field,value))throw Error(field.type==="integer"?"Введите целое число.":"Значение не соответствует типу поля.");
  if(field.choices&&!field.choices.some(choice=>JSON.stringify(choiceValue(choice))===JSON.stringify(value)))throw Error("Выберите одно из разрешённых значений.");
  if(typeof value==="number"){
   if(field.min!==undefined&&value<field.min)throw Error("Минимум: "+field.min+".");
   if(field.max!==undefined&&value>field.max)throw Error("Максимум: "+field.max+".");
   if(field.step!==undefined){const steps=(value-(field.min??0))/field.step;if(Math.abs(steps-Math.round(steps))>1e-7)throw Error("Допустимый шаг: "+field.step+".");}
  }
  return value;
 }
 function fieldHelp(field){
  const bounds=[];
  if(field.min!==undefined)bounds.push("минимум "+field.min);
  if(field.max!==undefined)bounds.push("максимум "+field.max);
  if(field.step!==undefined)bounds.push("шаг "+field.step);
  if(field.choices)bounds.push("допустимо: "+field.choices.map(choice=>choice&&typeof choice==="object"&&choice.label!==undefined?choice.label:String(choiceValue(choice))).join(", "));
  return field.help+(bounds.length?" · "+bounds.join(" · "):"");
 }
 function markError(id,message){
  if(message)errors.set(id,message);else errors.delete(id);
  const control=controls.get(id);if(!control)return;
  control.error.textContent=message||"";control.error.hidden=!message;
  control.input.setAttribute("aria-invalid",String(!!message));
 }
 function buildForm(){
  const target=$("config-fields");target.replaceChildren();controls.clear();
  if(!snapshot){target.append(make("div","empty","Нет свежей конфигурации сервера. Значения появятся после подключения."));return;}
  let index=0;
  for(const [policy,title,description] of [["hot","Горячие настройки","Применяются на работающем сервере. Текущие операции сохраняют свой прогресс."],["restart","Требуют перезапуска","Сохраняются перед перезапуском. Новые параметры вступят в силу после запуска сервера."]]){
   const group=make("article","panel config-group"),heading=make("div","panel-heading"),intro=make("div");intro.append(make("h2","",title),make("p","",description));heading.append(intro);group.append(heading);let count=0;
   for(const section of snapshot.server.sections){
    const fields=section.fields.filter(field=>field.policy===policy);if(!fields.length)continue;
    const block=make("section","config-section");block.append(make("h3","",section.label));
    for(const field of fields){
     count++;const id=fieldId(section.id,field.key),domId="config-input-"+(++index),row=make("div","config-field"),label=make("label","config-label",field.label);
     label.setAttribute("for",domId);
     const edit=make("div","config-value"),input=make(field.choices?"select":field.type==="json"?"textarea":"input"),help=make("p","config-help",fieldHelp(field)),error=make("p","config-error");
     input.id=domId;input.dataset.section=section.id;input.dataset.key=field.key;help.id=domId+"-help";error.id=domId+"-error";error.hidden=true;error.setAttribute("role","status");
     input.setAttribute("aria-describedby",help.id+" "+error.id);input.setAttribute("aria-invalid","false");
     const raw=drafts.has(id)?drafts.get(id):field.choices?field.value:rawValue(field,field.value);
     if(field.choices){
      field.choices.forEach((choice,i)=>{const option=make("option","",choice&&typeof choice==="object"&&choice.label!==undefined?choice.label:String(choiceValue(choice)));option.value=String(i);input.append(option);});
      input.value=String(field.choices.findIndex(choice=>JSON.stringify(choiceValue(choice))===JSON.stringify(raw)));
     }else if(field.type==="boolean"){input.type="checkbox";input.checked=raw;input.setAttribute("aria-label",field.label);}
     else{
      if(field.type!=="json")input.type=field.type==="number"||field.type==="integer"?"number":"text";
      if(field.type==="json"){input.rows=6;input.spellcheck=false;}
      input.value=raw;
      for(const bound of ["min","max","step"])if(field[bound]!==undefined)input.setAttribute(bound,field[bound]);
      if(field.type==="number"&&field.step===undefined)input.setAttribute("step","any");
     }
     const update=()=>{
      const raw=field.choices?choiceValue(field.choices[Number(input.value)]):field.type==="boolean"?input.checked:input.value;
      let unchanged=false;try{unchanged=JSON.stringify(parse(field,raw))===JSON.stringify(field.value);}catch{}
      if(unchanged)drafts.delete(id);else drafts.set(id,raw);
      markError(id,"");row.className="config-field"+(drafts.has(id)?" config-dirty":"");render();
     };
     input.addEventListener(field.choices||field.type==="boolean"?"change":"input",update);
     controls.set(id,{input,error,row,field});edit.append(input,help,error);row.append(label,edit);block.append(row);markError(id,errors.get(id));row.className="config-field"+(drafts.has(id)?" config-dirty":"");
    }
    group.append(block);
   }
   if(!count)group.append(make("div","empty","Доступных параметров этой группы нет."));target.append(group);
  }
 }
 function render(){
  const hot=entries().filter(({field,id})=>field.policy==="hot"&&drafts.has(id)).length;
  const restart=entries().filter(({field,id})=>field.policy==="restart"&&drafts.has(id)).length;
  const blocked=sending||reading||!!pending||!!restarting;
  const available=availability==="ready"&&!stale&&!!snapshot;
  $("config-edit").disabled=blocked;
  $("config-edit").setAttribute("aria-pressed",String(editing));
  $("config-apply").disabled=blocked||!available||!editing||hot===0;
  $("config-restart").disabled=blocked||!available||!editing||hot+restart===0;
  $("config-pending").textContent=hot+restart?"Несохранённые изменения: "+hot+" горячих · "+restart+" с перезапуском.":editing?"Форма открыта для редактирования.":"Параметры доступны для просмотра. Нажмите «Редактировать», чтобы получить свежие значения и подсказки.";
  $("config-state").textContent=sending?"Проверка и применение настроек…":pending?"Результат отправки неизвестен. Проверяем квитанцию того же запроса.":restarting?"Настройки сохранены; ожидается подтверждение перезапуска.":reading?"Чтение актуальной конфигурации…":stale?"Конфигурация сервера изменилась. Нажмите «Редактировать», чтобы обновить значения и подсказки; ваши правки сохранятся.":availability==="ready"?"Свежая конфигурация сервера · ревизия "+snapshot.server.revision.slice(0,8):availability==="offline"?"Сервер недоступен. Показанные значения и правки не обновлены.":availability==="stale"?"Данные конфигурации устарели. Применение недоступно до свежего чтения.":"Свежая конфигурация неизвестна. Применение недоступно.";
  $("config-result").textContent=resultText;
  for(const {input,field} of controls.values()){
   const locked=!editing||sending||!!pending||!!restarting;
   if(field.type==="boolean"||field.choices)input.disabled=locked;else{input.readOnly=locked;input.disabled=sending||!!pending||!!restarting;}
  }
 }
 async function request(url,options={}){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),options.method==="POST"?15000:7000);
  try{const response=await fetch(url,{...options,cache:"no-store",signal:controller.signal});return {ok:response.ok,status:response.status,body:await response.json()};}
  finally{clearTimeout(timer);}
 }
 function acceptSnapshot(next,preserve){
  const oldEpoch=snapshot?.server.worldEpoch;
  snapshot=next;availability="ready";stale=false;
  if(!preserve){drafts.clear();errors.clear();}
  const known=new Set(entries().map(entry=>entry.id));
  for(const id of drafts.keys())if(!known.has(id))drafts.delete(id);
  if(preserve){
   for(const {field,id} of entries())if(drafts.has(id)){
    try{if(JSON.stringify(parse(field,drafts.get(id)))===JSON.stringify(field.value))drafts.delete(id);}catch{}
   }
   if(oldEpoch&&oldEpoch!==next.server.worldEpoch)resultText="Сервер работает в другой эпохе мира. Проверьте сохранённые правки перед применением.";
  }
  buildForm();
 }
 async function readSnapshot(forEdit=false){
  const response=await request("/api/config");
  if(!response.ok||!validSnapshot(response.body)){
   const state=response.body?.state;availability=state==="stale"?"stale":["offline","unavailable","restart_or_server_required"].includes(state)?"offline":"unknown";
   return false;
  }
  const next=response.body;availability="ready";
  if(forEdit||!snapshot||!editing&&!drafts.size)acceptSnapshot(next,forEdit&&drafts.size>0);
  else if(next.server.revision!==snapshot.server.revision||next.server.worldEpoch!==snapshot.server.worldEpoch)stale=true;
  return true;
 }
 function outcome(envelope,operation){
  if(envelope?.schema!==1||envelope.request_id!==operation.request_id)return false;
  const result=envelope.result;
  if(envelope.state!=="completed"||result?.schema!==1||result.requestId!==operation.request_id)return false;
  if(result.state==="rejected"&&result.accepted===false){
   pending=null;
   const details=[];let marked=0;
   for(const error of result.errors||[]){
    const message=typeof error?.message==="string"&&error.message.trim()?error.message:typeof error?.code==="string"&&error.code.trim()?error.code:"Причина не указана.";
    const id=fieldId(error?.section,error?.key);
    if(controls.has(id)){markError(id,message);marked++;}
    else{
     const section=snapshot.server.sections.find(section=>section.id===error?.section);
     const label=section?.label||(typeof error?.section==="string"&&error.section?error.section:"Сервер");
     details.push(label+(typeof error?.key==="string"&&error.key?" / "+error.key:"")+": "+message);
    }
   }
   resultText="Сервер отклонил изменения. Ваши правки сохранены."+(marked?" Проверьте отмеченные поля.":"")+(details.length?" "+details.join(" "):marked?"":" Причина не указана.");
   if((result.errors||[]).some(error=>["stale_revision","revision_mismatch","stale_epoch","world_epoch_mismatch"].includes(error.code)))stale=true;
   return true;
  }
  if(result.accepted!==true||!["applied","restart_pending"].includes(result.state)||!/^[a-f0-9]{64}$/.test(result.revision))return false;
  pending=null;
  if(result.state==="restart_pending"){
   restarting={request_id:operation.request_id,world_epoch:operation.world_epoch,revision:result.revision};
   resultText="Сохранено. Мир будет сохранён, игроки отключатся; ожидается перезапуск сервера.";
  }else{
   for(const [id,raw] of operation.drafts){
    if(drafts.get(id)===raw)drafts.delete(id);
   }
   for(const {section,field} of entries())if(Object.hasOwn(operation.changes[section.id]||{},field.key))field.value=operation.changes[section.id][field.key];
   snapshot.server.revision=result.revision;
   resultText="Горячие настройки применены."+(drafts.size?" Изменения с перезапуском остаются в форме.":"");
   buildForm();
  }
  return true;
 }
 async function checkReceipt(){
  if(!pending||sending)return;
  const operation=pending;
  try{const response=await request("/api/config/receipt?request_id="+encodeURIComponent(operation.request_id));if(response.ok)outcome(response.body,operation);}catch{}
 }
 async function checkRestart(){
  if(!restarting)return;
  try{
   const response=await request("/api/config/restart"),state=response.body,operation=restarting;
   if(!response.ok||state?.schema!==1||state.requestId!==operation.request_id)return;
   const messages={queued:"Сохранено. Перезапуск поставлен в очередь; мир будет сохранён, игроки отключатся.",stopping:"Сохранение мира и остановка сервера. Игроки отключаются.",starting:"Мир сохранён. Сервер запускается с новой конфигурацией."};
   if(messages[state.state])resultText=messages[state.state];
   else if(state.state==="ready"&&state.worldEpoch===operation.world_epoch&&state.revision===operation.revision){
    restarting=null;drafts.clear();errors.clear();editing=false;resultText="Сервер готов. Новая конфигурация подтверждена после перезапуска.";
   }else if(state.state==="failed"){
    restarting=null;availability="stale";stale=true;
    resultText=state.rollback?"Запуск с новой конфигурацией не удался. Выполнен возврат к предыдущим настройкам; обновите форму перед изменениями.":"Перезапуск не подтверждён. Проверьте сервер и обновите форму; правки сохранены.";
   }
  }catch{}
 }
 async function refresh(){
  if(reading||sending)return;reading=true;render();
  try{
   await checkReceipt();await checkRestart();
   if(!pending&&!restarting)await readSnapshot();
  }catch{availability="offline";}
  finally{reading=false;render();}
 }
 async function edit(){
  if(reading||sending||pending||restarting)return;reading=true;render();
  try{if(await readSnapshot(true)){editing=true;resultText=drafts.size?"Схема, границы и подсказки обновлены. Проверьте сохранённые правки.":"Редактирование открыто. Подсказки и границы получены с сервера.";}}
  catch{availability="offline";}
  finally{reading=false;render();}
 }
 async function apply(mode){
  if(!["hot","restart"].includes(mode)||sending||reading||pending||restarting||!editing||availability!=="ready"||stale||!snapshot)return;
  const changes=Object.create(null),sentDrafts=new Map();let firstInvalid=null;
  for(const {section,field,id} of entries()){
   if(!drafts.has(id)||mode==="hot"&&field.policy!=="hot")continue;
   try{
    const value=parse(field,drafts.get(id));if(!changes[section.id])changes[section.id]=Object.create(null);changes[section.id][field.key]=value;sentDrafts.set(id,drafts.get(id));markError(id,"");
   }catch(error){markError(id,error.message);if(!firstInvalid)firstInvalid=controls.get(id)?.input;}
  }
  if(firstInvalid){resultText="Проверьте отмеченные поля. Изменения не отправлены.";firstInvalid.focus();render();return;}
  if(!sentDrafts.size)return;
  const operation={request_id:crypto.randomUUID(),world_epoch:snapshot.server.worldEpoch,revision:snapshot.server.revision,mode,changes,drafts:sentDrafts};
  const {drafts:ignored,...body}=operation;
  sending=true;pending=operation;render();
  try{
   const response=await request("/api/config/apply",{method:"POST",headers:{"Content-Type":"application/json","X-Dead-Earth-Operator":snapshot.nonce},body:JSON.stringify(body)});
   if(!outcome(response.body,operation)){
    if([400,403,409,413].includes(response.status)&&typeof response.body?.error==="string"){
     pending=null;stale=true;
     resultText=response.status===403?"Сеанс оператора изменился. Запрос отклонён до применения; правки сохранены. Нажмите «Редактировать», чтобы обновить доступ.":"Запрос отклонён до применения. Правки сохранены. Нажмите «Редактировать», чтобы обновить конфигурацию и проверить значения.";
    }else resultText="Результат запроса неизвестен. Проверяем квитанцию; повторная отправка отключена.";
   }
   if(response.status===409)stale=true;
  }catch{resultText="Результат запроса неизвестен: настройки могли сохраниться. Проверяем квитанцию; запрос автоматически не повторяется.";}
  finally{sending=false;render();}
  await refresh();
 }
 $("config-edit").addEventListener("click",edit);
 $("config-apply").addEventListener("click",()=>apply("hot"));
 $("config-restart").addEventListener("click",()=>apply("restart"));
 $("config-restart-notice").textContent="При применении с перезагрузкой мир сохраняется, игроки отключаются. Статус запуска появится здесь после подтверждения сервера.";
 window.DeadEarthConfiguration={render,refresh,edit,apply};
 buildForm();render();refresh();
 setInterval(()=>{if(!$("configuration").hidden||pending||restarting)refresh();},5000);
})();
