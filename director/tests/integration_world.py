"""Opt-in real model + Minecraft check; prints no bridge credentials."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import sys
import time
from urllib.request import urlopen
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from dead_earth_director.bridge import Descriptor,BridgeClient
from dead_earth_director.host import Director,Journal,load_config
from dead_earth_director.model import OllamaModel,ModelSettings

def docker(action):
    subprocess.run(["wsl","-d","Ubuntu","-u","root","--","docker",action,"dead-earth-ollama"],
                   check=True,timeout=20,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--world",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    deadline=time.monotonic()+180
    marker=args.world/"director-d1-ready.json"
    while not marker.is_file():
        if time.monotonic()>deadline: raise RuntimeError("test_world_not_ready")
        time.sleep(.5)
    settings,_=load_config(root/"director.example.toml")
    descriptor=Descriptor.load(args.world/"dead_earth_bridge.json")
    client=BridgeClient(descriptor)
    journal=Journal(args.world/"director-model.sqlite3")
    samples=[];maximum_vram=0;maximum_size=0;observed=0
    stopped=False
    result=dict(ok=False)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            def invoke():
                worker=Journal(args.world/"director-model.sqlite3")
                try: return Director(client,OllamaModel(settings),worker).once()
                finally: worker.close()
            future=pool.submit(invoke)
            while not future.done():
                view=client.call("get_director_brief",{})
                samples.append((time.monotonic(),view["planning"]["activeTicks"]))
                try:
                    with urlopen(settings.url+"/api/ps",timeout=2) as response:
                        models=json.load(response).get("models",[])
                    for row in models:
                        observed+=1
                        maximum_vram=max(maximum_vram,row.get("size_vram",0))
                        maximum_size=max(maximum_size,row.get("size",0))
                except OSError: pass
                time.sleep(1)
            report=future.result()
        duplicate=Director(client,OllamaModel(settings),journal).once()
        docker("stop");stopped=True
        fallback_journal=Journal(args.world/"director-fallback.sqlite3")
        try:
            offline=ModelSettings(settings.url,settings.model,settings.digest,timeout_seconds=1)
            fallback=Director(client,OllamaModel(offline),fallback_journal).once()
        finally: fallback_journal.close()
        tps=None
        if len(samples)>=2 and samples[-1][0]>samples[0][0]:
            tps=round((samples[-1][1]-samples[0][1])/(samples[-1][0]-samples[0][0]),2)
        result=dict(ok=report["source"]=="model" and report["state"]=="preview_accepted"
                    and fallback["source"]=="fallback" and fallback["state"]=="preview_accepted"
                    and duplicate["state"]=="already_recorded" and observed>0 and maximum_vram==0,
                    model=report,fallback=fallback,duplicate_state=duplicate["state"],
                    model_observations=observed,maximum_vram_bytes=maximum_vram,maximum_model_bytes=maximum_size,
                    observed_server_tps=tps,clock_samples=len(samples),gpu_requested=False,threads=4,
                    note="One embedded test player. No six-player or rendered-client load claim.")
    except Exception as error:
        result=dict(ok=False,error_type=type(error).__name__,error_code=str(error) if isinstance(error,BridgeError) else "integration_failed",maximum_vram_bytes=maximum_vram)
    finally:
        journal.close()
        if stopped: docker("start")
        args.output.parent.mkdir(parents=True,exist_ok=True)
        data=json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)+"\n"
        args.output.write_text(data,encoding="utf-8")
        temporary=args.world/"director-d1-result.tmp"
        temporary.write_text(data,encoding="utf-8")
        temporary.replace(args.world/"director-d1-result.json")
    print(json.dumps(result,ensure_ascii=True,allow_nan=False))
    return 0 if result["ok"] else 1

if __name__=="__main__":
    raise SystemExit(main())
