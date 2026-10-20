"""Final-stage real CPU Ollama + isolated native SRP bridge verification. No fixed selector."""
import argparse
from dataclasses import replace
import json
from pathlib import Path
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'director'))
from dead_earth_director.bridge import BridgeClient,Descriptor
from dead_earth_director.host import CinematicDirector,Journal,load_config,cinematic_context,build_model
from dead_earth_director.ai_control import ModeStore,RuntimeModels,critic_residents
from dead_earth_director.council import CouncilModel


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--server-dir',required=True,type=Path);parser.add_argument('--native-fixture',action='store_true')
    args=parser.parse_args();root=args.server_dir.resolve(strict=True)
    if root.parent!=(ROOT/'work').resolve() or not (root/'.dead-earth-isolated-native-test').is_file():raise ValueError('Only marked isolated repository work fixtures are permitted')
    if args.native_fixture:
        native=json.loads((root/'port-probe.json').read_text(encoding='utf-8'));restore=json.loads((root/'restart-probe.json').read_text(encoding='utf-8'))
        if native.get('passed') is not True or restore.get('passed') is not True:raise ValueError('Native fixture and physical restart must pass first')
        showcase=json.loads((root/'probe_world/data/dead_earth_legacy.json').read_text(encoding='utf-8'))
    else:showcase=json.loads((root/'client-showcase.json').read_text(encoding='utf-8'))
    descriptor=Descriptor.load(root/'probe_world/dead_earth_bridge.json')
    if descriptor.world_epoch!=showcase['worldEpoch']:raise ValueError('Test world epoch mismatch')
    report={'schema':1,'kind':'real_cpu_ollama_native_bridge','passed':False,'checks':[],'models_mocked':False,'player_fixture':'native_fake_survival_player' if args.native_fixture else 'real_network_player'}
    runtime=root/('real-ai-'+uuid.uuid4().hex[:8]);runtime.mkdir(exist_ok=False)
    journal=None;models=None
    def require(value,name):
        report['checks'].append({'name':name,'passed':bool(value)})
        if not value:raise AssertionError(name)
    try:
        client=BridgeClient(descriptor);snapshot=client.call('get_snapshot',{})['snapshot']
        require(type(snapshot.get('onlinePlayers')) is int and snapshot['onlinePlayers']>0,'native survival participant online')
        view=client.call('get_cinematic_brief',{});require(view.get('status')=='ready','native finite goal ready')
        goal,context=cinematic_context(view,descriptor)
        report['context']=context;report['goal_id']=goal['id']
        settings,_=load_config(root/'director/director.example.toml')
        require(settings.url=='http://127.0.0.1:11434' and settings.threads<=4,'pinned CPU loopback settings')
        store=ModeStore(runtime/'ai-control.sqlite3');models=RuntimeModels(settings,store,'live')
        primary=models.refresh();journal=Journal(runtime/'decisions.sqlite3')
        result=CinematicDirector(client,primary,journal).once();report['single']=result
        require(result.get('source')=='model' and result.get('reason')=='model_validated','actual primary response parsed without fallback')
        require(result.get('state') in {'queued','already_queued','already_recorded'} and result.get('operation_id'),'real primary choice admitted by native server')
        saved=journal.read(result['goal_id']);report['single_choice']=saved['choice']
        require(saved['choice']['audit']['campaign_id']==descriptor.campaign_id,'actual journal tied to campaign')
        queue=client.call('get_cinematic_queue',{});ids={row.get('id',row.get('operationId')) for row in queue.get('operations',[])+queue.get('history',[])}
        require(result['operation_id'] in ids,'actual operation present in native queue/history')
        desired=store.desired(settings.ai_mode)
        store.request({'request_id':str(uuid.uuid4()),'expected_revision':desired['revision'],'mode':'dual'},settings)
        dual=models.refresh();require(isinstance(dual,CouncilModel),'runtime applies dual mode')
        choice,metrics=dual.select(context);report['dual']={'choice':choice,'metrics':metrics}
        stages=metrics.get('stages',[])
        require(any(row.get('stage')=='proposal' and row.get('status')=='valid' for row in stages),'actual dual primary stage valid')
        require(any(row.get('stage')=='critique' and row.get('status')=='valid' for row in stages),'actual second model critique valid')
        require(type(choice.get('option')) is int and 0<=choice['option']<len(context['options']),'actual dual selection stays within allowed options')
        desired=store.desired(settings.ai_mode)
        store.request({'request_id':str(uuid.uuid4()),'expected_revision':desired['revision'],'mode':'single'},settings)
        models.refresh()
        require(not critic_residents(settings,deadline=time.monotonic()+5),'single mode confirms critic absent from RAM')
        report['model_pins']={'primary':settings.model,'primary_digest':settings.digest,'critic':settings.critic.model,'critic_digest':settings.critic.digest}
        report['cpu_request_options']={'num_gpu':0,'num_thread':settings.threads,'num_ctx':settings.context,'keep_alive':0}
        report['manual_review_required']=True;report['passed']=True
    except Exception as failed:
        report['error']=str(failed)
    finally:
        if journal:journal.close()
        if models:models.close()
        (runtime/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({key:value for key,value in report.items() if key not in {'context','single_choice'}},ensure_ascii=False,indent=2),flush=True)
    return 0 if report['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
