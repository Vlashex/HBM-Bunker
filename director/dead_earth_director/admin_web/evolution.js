"use strict";
window.DeadEarthEvolution=(()=>{
 const byId=id=>document.getElementById(id),format=new Intl.NumberFormat('ru-RU',{maximumFractionDigits:2});
 let state=null,loading=false,sending=false,initialized=false;
 const messages={ready:'Состояние эволюции получено',paused:'Автоматический рост на паузе: игроков нет.',disabled:'Контроллер эволюции выключен.',offline:'Сервер эволюции недоступен. Проверьте соединение и RCON.',unsupported:'Сервер не поддерживает управляемую эволюцию. Требуется обновление мода.',
  unsupported_save:'Сохранение эволюции не поддерживается. Сдвиги заблокированы.',configuration_error:'Ошибка конфигурации эволюции. Сдвиги заблокированы.',unavailable:'Эволюция недоступна. Сдвиги заблокированы.',
  phase_limit:'Достигнута граница фаз. Изменение не выполнено.',journal_full:'Журнал квитанций заполнен. Изменение не выполнено.',busy:'Другая команда ещё выполняется. Изменение не отправлено.',
  stale_revision:'Запрос устарел: состояние изменилось. Данные обновлены; повторной отправки нет.',stale_epoch:'Эпоха мира изменилась. Данные обновлены; повторной отправки нет.',stale_campaign:'Кампания изменилась. Данные обновлены; повторной отправки нет.',
  rejected:'RCON отклонил авторизацию. Изменение не отправлено.',uncertain:'Исход неизвестен. Изменение могло выполниться. Проверьте фазу и историю перед новым действием; автоматического повтора нет.',
  conflicting_request:'Эта квитанция относится к другому действию. Изменение не отправлено.',invalid_evolution_request:'Некорректный запрос эволюции. Обновите состояние.',operator_session_required:'Сессия оператора изменилась. Обновите состояние.'};
 const text=(tag,value)=>{const node=document.createElement(tag);node.textContent=String(value);return node;};
 const note=value=>{byId('evolution-result').textContent=value;};
 function phaseMaximum(server){return server?.parasiteSystem==='srp'&&server.phaseMaximum===10?10:5;}
 function permitted(){const server=state?.server;return !sending&&state?.state==='ready'&&server?.enabled&&['ready','paused'].includes(server.status)&&Number.isSafeInteger(server.revision)&&Number.isInteger(server.phase)&&server.phase>=0&&server.phase<=phaseMaximum(server);}
 function controls(){byId('evolution-minus').disabled=!permitted()||state.server.phase<=0;byId('evolution-plus').disabled=!permitted()||state.server.phase>=phaseMaximum(state.server);}
 function list(id,items,empty){const node=byId(id);node.replaceChildren();for(const item of items||[])node.append(text('li',item));if(!items?.length)node.append(text('li',empty));}
 function render(){
  initialized=true;
  const server=state?.server,online=state?.state==='ready'&&server;
  byId('evolution-state').textContent=online?(!server.enabled&&['ready','paused'].includes(server.status)?messages.disabled:messages[server.status]||messages.ready):(messages[state?.state]||messages.offline);
  byId('evolution-phase').textContent=online?'Фаза '+server.phase+' / '+phaseMaximum(server):'Фаза —';
  byId('evolution-name').textContent=online?server.name:'Нет свежего состояния';
  const progress=online?Math.max(0,Math.min(1,server.progress)):0;
  byId('evolution-progress').value=progress;byId('evolution-progress').setAttribute('aria-valuetext',format.format(progress*100)+'%');
  byId('evolution-progress-label').textContent=online?(server.nextPhasePoints===null?'Последняя фаза':format.format(progress*100)+'% до следующей фазы'):'Прогресс недоступен';
  byId('evolution-summary').textContent=online?format.format(server.points)+' очков · начало фазы '+format.format(server.phaseStartPoints)+(server.nextPhasePoints===null?'':' · следующая '+format.format(server.nextPhasePoints))+' · '+format.format(server.rate)+' очков за активный тик · версия '+server.revision:'';
  byId('evolution-pause').textContent=online&&(server.paused||server.status==='paused')?'Пауза: игроков нет. Автоматический рост остановлен.':online?'Рост учитывает активное время кампании при присутствии игроков.':'';
  byId('evolution-migration').textContent=online?({active_campaign_recalculation:'Перенос: очки пересчитаны из активного времени существующей кампании.',new_campaign:'Эволюция создана для новой кампании.'}[server.migration]||'Сведения о переносе недоступны.'):'';
  list('evolution-entities',online?server.unlockedEntities:[],'Открытые формы не получены.');
  list('evolution-abilities',online?server.abilities:[],'Открытых способностей пока нет.');
  const history=byId('evolution-history');history.replaceChildren();
  for(const item of online?server.history:[]){const row=text('li','Фаза '+item.from+' → '+item.to+' · тик '+item.atTick+' · '+({operator:'оператор',automatic:'автоматически',migration:'перенос'}[item.source]||item.source));if(item.requestId)row.append(text('small','Квитанция '+item.requestId));history.append(row);}
  if(!history.children.length)history.append(text('li','Переходов пока нет.'));
  controls();
 }
 async function refresh(){
  if(loading)return;loading=true;const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
  try{const response=await fetch('/api/evolution',{cache:'no-store',signal:controller.signal});if(!response.ok)throw Error();state=await response.json();render();}
  catch{state={state:'offline',server:null};render();}
  finally{clearTimeout(timer);loading=false;}
 }
 async function shift(delta){
  if(!permitted()||![-1,1].includes(delta)||(delta<0&&state.server.phase<=0)||(delta>0&&state.server.phase>=phaseMaximum(state.server)))return;
  const server=state.server,nonce=state.nonce,requestId=crypto.randomUUID();
  const payload={request_id:requestId,campaign_id:server.campaignId,world_epoch:server.worldEpoch,revision:server.revision,delta};
  sending=true;controls();note('Изменение фазы… Квитанция '+requestId);
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),12000);
  try{
   const response=await fetch('/api/evolution/shift',{method:'POST',headers:{'Content-Type':'application/json','X-Dead-Earth-Operator':nonce},body:JSON.stringify(payload),signal:controller.signal});
   const result=await response.json();
   if(!response.ok){note((messages[result.error]||messages.uncertain)+' · квитанция '+requestId);}
   else if(result.state==='completed'&&result.result){
    if(result.result.evolution)state={...state,server:result.result.evolution};
    note((result.result.accepted?'Фаза изменена.':messages[result.result.code]||('Изменение отклонено: '+result.result.code))+' · квитанция '+requestId);
   }else note((messages[result.state]||messages.uncertain)+' · квитанция '+requestId);
  }catch{note(messages.uncertain+' Квитанция '+requestId);}
  finally{clearTimeout(timer);sending=false;await refresh();controls();}
 }
 byId('evolution-minus').addEventListener('click',()=>shift(-1));
 byId('evolution-plus').addEventListener('click',()=>shift(1));
 byId('evolution-refresh').addEventListener('click',refresh);
 setInterval(()=>{if(initialized&&typeof view!=='undefined'&&view==='evolution'&&!paused)refresh();},10000);
 return {render:()=>{if(!state&&!loading)refresh();else render();},refresh,shift};
})();
if(typeof view!=='undefined'&&view==='evolution')window.DeadEarthEvolution.refresh();
