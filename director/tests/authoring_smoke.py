"""Explicit HTTP -> RCON smoke, confined to our separate authoring test server."""
import argparse
import copy
import http.client
import json
from pathlib import Path
import sys
import threading
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from dead_earth_director.admin import Application, make_server
from dead_earth_director.admin_console import read_properties, settings, rcon_command


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--server-root',type=Path,required=True);args=parser.parse_args()
    root=args.server_root.resolve();expected=Path(__file__).resolve().parents[2]/'mods/dead-earth'
    assert root.parent==expected and root.name.startswith('run-server-authoring-'),'isolated test root required'
    properties=read_properties(root/'server.properties')
    assert properties['server-port']=='25566' and properties['rcon.port']=='25576','test ports required'
    catalog_path=root/'config/dead-earth/director/catalog.json';original=catalog_path.read_bytes()
    app=Application(root/'authoring_test/dead_earth_bridge.json',root/'director/runtime/decisions.sqlite3',root/'director/runtime/admin.sqlite3',catalog_path,server_root=root)
    server=make_server(app,0);worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start();port=server.server_address[1]
    def request(method,path,payload=None):
        headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{port}','X-Dead-Earth-Operator':app.authoring_nonce}
        conn=http.client.HTTPConnection('127.0.0.1',port,timeout=15)
        conn.request(method,path,json.dumps(payload) if payload else None,headers)
        response=conn.getresponse();result=response.status,json.loads(response.read());conn.close();return result
    def action(kind,payload,ident=None):
        value=dict(request_id=ident or str(uuid.uuid4()),**payload);status,result=request('POST','/api/authoring/'+kind,value)
        assert status==200,(kind,status,result);return result
    try:
        status,initial=request('GET','/api/authoring');assert status==200 and initial['state']=='ready',initial['state']
        epoch=initial['server']['worldEpoch'];revision=initial['server']['catalogRevision'];scope=dict(world_epoch=epoch,catalog_revision=revision)
        catalog=copy.deepcopy(initial['catalog']);event=copy.deepcopy(catalog['events'][0]);event['id']='custom_http_smoke';event['title']='HTTP RCON safe smoke'
        sound=next(iter(catalog['sounds']));event.update(start='cue',seasons=['CALM','HEAT','ASH_WINTER'],heavy=False,minPlayers=1,minScore=0,
            nodes=[dict(id='cue',actions=[dict(type='sound',key=sound,role='approach',portion=0)],waitTicks=20,branches=[],timeout='end'),
                   dict(id='end',actions=[dict(type='finish',key='inconclusive',role='approach',portion=0)],waitTicks=0,branches=[],timeout='')])
        saved=action('save',dict(event=event,draft_revision=0));assert saved['state']=='saved',saved
        draft=dict(draft_id=event['id'],draft_revision=1,**scope)
        validated=action('validate',draft);assert validated['result']['code']=='validated',validated
        assert catalog_path.read_bytes()==original,'validation changed live catalog'
        ident=str(uuid.uuid4());published=action('publish',draft,ident);assert published['result']['code']=='applied',published
        _,after=request('GET','/api/authoring');assert after['state']=='ready'
        assert after['server']['catalogRevision']!=revision
        for key in ('sounds','traces','waves','rules'):assert after['catalog'][key]==catalog[key]
        assert len(after['catalog']['events'])==len(catalog['events'])+1
        backups=list((catalog_path.parent/'backups').glob('*.json'));assert len(backups)==1 and backups[0].read_bytes()==original
        assert action('publish',draft,ident)==published,'duplicate publish did not replay receipt'
        assert len(list((catalog_path.parent/'backups').glob('*.json')))==1,'duplicate publish applied twice'
        scope['catalog_revision']=after['server']['catalogRevision']
        selection=dict(event_id=event['id'],zone_id=str(uuid.uuid4()),**scope)
        for kind in ('preview','run'):
            rejected=action(kind,selection);assert rejected['result']['accepted'] is False and rejected['result']['code'] in ('disabled','no_players','ineligible'),rejected
        cancelled=action('cancel',dict(world_epoch=epoch,operation_id=str(uuid.uuid4())))
        assert cancelled['result']['code']=='not_found',cancelled
        # Marker text and scope read are insufficient: native status must agree with bytes after publication.
        assert app.authoring.server_status()['sourceHash']==after['server']['sourceHash']
        print(json.dumps(dict(state='passed',chain='HTTP -> typed operator commands -> real Minecraft RCON',saved=True,validated=True,published=True,
                             replay_without_second_apply=True,source_hash_matches=True,backup_exact=True,no_player_run_rejected=True,missing_operation_cancel_rejected=True),ensure_ascii=False))
    finally:
        server.shutdown();server.server_close();worker.join()
        state,port,password=settings(root)
        if state=='configured':rcon_command(port,password,'stop')


if __name__=='__main__':main()
