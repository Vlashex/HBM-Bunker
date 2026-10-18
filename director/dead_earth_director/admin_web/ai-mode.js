"use strict";
(()=>{
 const $=id=>document.getElementById(id);
 let data=null,reading=false,sending=false;
 function render(){
  const valid=!!(data&&["single","dual"].includes(data.desired_mode)&&typeof data.nonce==="string");
  for(const mode of ["single","dual"]){const button=$(mode==="single"?"ai-single":"ai-dual");
   button.setAttribute("aria-pressed",String(valid&&data.desired_mode===mode));
   button.disabled=!valid||sending||(mode==="dual"&&!data.dual_available)||
    (data.desired_mode===mode&&data.state==="applied"&&(mode==="dual"||data.critic_memory==="released"));
  }
  if(!valid){$("ai-mode-state").textContent="Управление ИИ недоступно. Проверьте конфигурацию и обновление host.";
   $("ai-mode-memory").textContent="Выгрузка второй модели не подтверждена.";return;}
  const selected=data.desired_mode==="single"?"одна модель":"две модели";
  $("ai-mode-state").textContent=data.state==="applied"?"Применено: "+selected+".":
   data.host_run_mode==="preview"?"Режиссёр работает в предпросмотре с одной моделью. Выбрано для live: "+selected+".":
   data.state==="pending"?"Переключение: "+selected+". Ожидается завершение текущего шага или подтверждение выгрузки.":
   "Сохранено: "+selected+". Работа обновлённого режиссёра пока не подтверждена.";
  $("ai-mode-memory").textContent=data.critic_memory==="released"?"Вторая модель: выгружена из ОЗУ.":
   data.critic_memory==="loaded"?"Вторая модель: загружена в ОЗУ.":"Выгрузка второй модели не подтверждена: Ollama недоступна.";
 }
 async function refresh(){
  if(reading||sending)return;reading=true;
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),5000);
  try{const response=await fetch("/api/ai-mode",{cache:"no-store",signal:controller.signal});
   if(!response.ok)throw Error();data=await response.json();
  }catch{data=null;}finally{clearTimeout(timer);reading=false;render();}
 }
 async function switchMode(mode){
  if(sending||reading||!data||!["single","dual"].includes(mode)||mode==="dual"&&!data.dual_available)return;
  sending=true;render();$("ai-mode-result").textContent="Применение режима…";
  const command={mode,request_id:crypto.randomUUID(),expected_revision:data.revision};
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);let stale=false;
  try{const response=await fetch("/api/ai-mode",{method:"POST",headers:{"Content-Type":"application/json","X-Dead-Earth-Operator":data.nonce},body:JSON.stringify(command),signal:controller.signal});
   if(response.status===409){stale=true;$("ai-mode-result").textContent="Настройка уже изменилась. Состояние обновлено; запрос повторно не отправлялся.";}
   else if(!response.ok){$("ai-mode-result").textContent="Режим не подтверждён. Проверьте конфигурацию моделей и обновите состояние.";}
   else{data=await response.json();$("ai-mode-result").textContent=data.state==="applied"?"Режим применён.":"Режим сохранён. Ожидается подтверждение режиссёра; статус обновляется автоматически.";}
  }catch{$("ai-mode-result").textContent="Результат запроса неизвестен: режим мог измениться. Обновите состояние перед новой отправкой.";}
  finally{clearTimeout(timer);sending=false;render();if(stale)await refresh();}
 }
 $("ai-single").addEventListener("click",()=>switchMode("single"));
 $("ai-dual").addEventListener("click",()=>switchMode("dual"));
 $("ai-mode-refresh").addEventListener("click",refresh);
 window.DeadEarthAiMode={refresh,switchMode};
 render();refresh();setInterval(refresh,5000);
})();
