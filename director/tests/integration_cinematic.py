"""Explicit normal-server + CPU-model + rendered-client verification; no descriptor output."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
import time
from urllib.request import urlopen
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from dead_earth_director.bridge import Descriptor,BridgeClient
from dead_earth_director.host import CinematicDirector,Journal,load_config
from dead_earth_director.model import OllamaModel

def read(path):
    try:return json.loads(path.read_text(encoding="utf-8"))
    except (OSError,ValueError):return {}

def main():
    p=argparse.ArgumentParser();p.add_argument("--world",type=Path,required=True);p.add_argument("--client",type=Path,required=True);p.add_argument("--output",type=Path,required=True)
    args=p.parse_args();deadline=time.monotonic()+420;settings,_=load_config(Path(__file__).resolve().parents[1]/"director.example.toml")
    while not (args.world/"director-cinematic-ready.json").is_file():
        status=read(args.world/"director-cinematic-server-result.json")
        if status.get("success") is False:raise RuntimeError("test_fixture_rejected: "+status.get("reason","unknown"))
        if time.monotonic()>deadline:raise RuntimeError("world_not_ready")
        time.sleep(.5)
    client=BridgeClient(Descriptor.load(args.world/"dead_earth_bridge.json"));observed=0;maximum_vram=0;samples=[];result=dict(ok=False)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            def invoke():
                journal=Journal(args.world/"cinematic-model.sqlite3")
                try:return CinematicDirector(client,OllamaModel(settings),journal).once()
                finally:journal.close()
            future=pool.submit(invoke)
            while not future.done():
                view=client.call("get_cinematic_brief",{});samples.append((time.monotonic(),view["activeTicks"]))
                try:
                    with urlopen(settings.url+"/api/ps",timeout=2) as response:
                        for model in json.load(response).get("models",[]):
                            observed+=1;maximum_vram=max(maximum_vram,model.get("size_vram",0))
                except OSError:pass
                time.sleep(1)
            report=future.result()
        result.update(model=report,loaded_model_observations=observed,maximum_vram_bytes=maximum_vram)
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        if report.get("state")!="queued" or report.get("source")!="model":raise RuntimeError("real_model_did_not_queue")
        journal=Journal(args.world/"cinematic-model.sqlite3")
        try:saved=journal.read(report["goal_id"])
        finally:journal.close()
        retry=client.call("choose_cinematic",dict(request_id=saved["request_id"],goal_id=report["goal_id"],
                         event_id=saved["choice"]["event_id"],zone_id=saved["choice"]["zone_id"]))
        result["duplicate"]=retry.get("code")
        if retry.get("code") not in {"already_queued","already_recorded"}:raise RuntimeError("duplicate_not_idempotent")
        while True:
            status=read(args.world/"director-cinematic-status.json");visual=read(args.client/"client-cinematic-result.json")
            if status.get("clientDone") and visual.get("ready"):break
            if visual and not visual.get("ready"):raise RuntimeError("client_scene_not_verified")
            server_result=read(args.world/"director-cinematic-server-result.json")
            if server_result.get("success") is False:raise RuntimeError("test_fixture_rejected: "+server_result.get("reason","unknown"))
            if time.monotonic()>deadline:raise RuntimeError("rendered_client_not_verified")
            time.sleep(1)
        screenshots=sorted((args.client/"screenshots").glob("*.png"),key=lambda f:f.stat().st_mtime)
        if not screenshots:raise RuntimeError("screenshot_missing")
        okay=observed>0 and maximum_vram==0
        result=dict(ok=okay,model=report,duplicate=retry["code"],loaded_model_observations=observed,maximum_vram_bytes=maximum_vram,
                    client=visual,screenshot=str(screenshots[-1].resolve()),server=status,
                    ordinary_clock_samples=len(samples))
    finally:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        (args.world/"director-cinematic-result.json").write_text(json.dumps(dict(ok=result["ok"])),encoding="utf-8")
    print(json.dumps({k:v for k,v in result.items() if k not in {"server","screenshot"}},ensure_ascii=False))
    return 0 if result["ok"] else 1

if __name__=="__main__":raise SystemExit(main())
