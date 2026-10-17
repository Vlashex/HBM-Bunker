"""Collect bounded, non-secret summaries; raw worlds, logs and recordings stay outside Git."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    return dict(bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def profile(folder):
    phases = defaultdict(lambda: dict(threads={}, events={}))
    for path in folder.glob('*jfr.tsv'):
        side = 'client' if path.name.startswith('client') else 'server'
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            parts = line.split('\t')
            kind, phase = parts[:2]
            if phase == 'outside_phases' or kind == 'EVENT':
                continue
            if kind == 'DURATION':
                row = phases[phase]['events'].setdefault(side, {}).setdefault(parts[2], dict(count=0, summedDurationMs=0))
                row['count'] += int(parts[3]); row['summedDurationMs'] += float(parts[4])
                continue
            if kind not in ('SAMPLES_EXEC', 'SAMPLES_NATIVE', 'GAME_EXEC', 'LEAF_EXEC', 'INCLUSIVE_EXEC'):
                continue
            thread = parts[2]
            if thread.startswith('Worker-Main-'): thread = side + ' Worker-Main pool'
            elif thread not in ('Server thread', 'Render thread'): thread = side + ' other threads'
            target = phases[phase]['threads'].setdefault(thread, dict(executionSamples=0, nativeSamples=0, game=Counter(), leaf=Counter(), inclusive=Counter()))
            if kind == 'SAMPLES_EXEC': target['executionSamples'] += int(parts[-2])
            elif kind == 'SAMPLES_NATIVE': target['nativeSamples'] += int(parts[-2])
            elif kind in ('GAME_EXEC', 'LEAF_EXEC', 'INCLUSIVE_EXEC'):
                target[dict(GAME_EXEC='game', LEAF_EXEC='leaf', INCLUSIVE_EXEC='inclusive')[kind]][parts[3]] += int(parts[-2])
    for row in phases.values():
        for thread, data in row['threads'].items():
            total = data['executionSamples']
            def reduced(counts, limit=12):
                return [dict(method=name, samples=count, executionSamplePercent=100*count/total if total else None)
                        for name, count in counts.most_common(limit)]
            data['topGameExecution'] = reduced(data.pop('game'))
            data['topLeafExecution'] = reduced(data.pop('leaf'))
            inclusive = data.pop('inclusive')
            data['deadEarthInclusiveExecution'] = reduced(Counter({name:count for name,count in inclusive.items()
                if name.startswith('dev.deadearth.') and '.loadtest.' not in name and '$$Lambda' not in name}))
            data['selectedInclusiveExecution'] = reduced(Counter({name:count for name,count in inclusive.items()
                if any(name.startswith(prefix) for prefix in ('software.bernie.geckolib.', 'com.mojang.blaze3d.vertex.',
                    'net.minecraft.world.entity.LivingEntity.push', 'net.minecraft.world.entity.ai.navigation.',
                    'net.minecraft.world.level.Level.getEntities', 'net.minecraft.world.level.entity.EntitySection.getEntities'))}))
    return dict(phases)


def collect(workspace, output):
    output.mkdir(parents=True, exist_ok=True)
    runs = {}
    for n in (3, 4, 5):
        folder = workspace / 'work/dead-earth' / f'performance-{n}'
        summary = read(folder / 'summary.json')
        for phase in summary['phases']:
            phase['run'] = n
        profiles = profile(folder)
        for phase in summary['phases']:
            events = profiles.get(phase['name'], {}).get('events', {}).get('server', {})
            phase['jfrChunkGenerationStageEvents'] = events.get('minecraft.ChunkGeneration', {}).get('count', 0)
            if phase.get('excludedReason'): phase['reportUse'] = 'rejected'
            elif phase.get('smokeOnly'): phase['reportUse'] = 'smoke_only'
            elif n == 3 and (phase['name'].startswith('baseline_') or phase['name'] == 'chunks_warm_64'):
                phase['reportUse'] = 'warmup_or_background_generation_context'
            else: phase['reportUse'] = phase['measurementKind']
        runs[str(n)] = dict(summary=summary, profiles=profiles, sourceFiles={})
        client_windows = [json.loads(line) for line in (folder/'client-metrics.jsonl').read_text(encoding='utf-8').splitlines()
                          if line.strip() and json.loads(line).get('type') == 'window']
        server_windows = [json.loads(line) for line in (folder/'server-metrics.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
        runs[str(n)]['observedMemory'] = dict(clientHeapPeakBytes=max(row['heapUsed'] for row in client_windows),
            clientHeapMaxBytes=max(row['heapMax'] for row in client_windows),
            serverFreePhysicalMinBytes=min(row['freePhysicalBytes'] for row in server_windows))
        for name in ('summary.json', 'phases.json', 'server-metrics.jsonl', 'client-metrics.jsonl',
                     'measurement-phases.tsv', 'motion-samples.json'):
            path = folder / name
            if path.is_file(): runs[str(n)]['sourceFiles'][name] = digest(path)
        for name in ('evidence-final.json', 'jfr-manifest.json', 'motion-samples.json'):
            path = folder / name
            if path.is_file(): runs[str(n)][name.removesuffix('.json')] = read(path)
    baseline = {}
    for name in ('mods.lock.json', 'own-mods.lock.json', 'shaders.lock.json'):
        result = subprocess.run(['git', '-c', 'safe.directory='+str(ROOT), 'show', 'v5.2.0:metadata/'+name],
                                cwd=ROOT, check=True, capture_output=True)
        baseline[name] = dict(sha256=hashlib.sha256(result.stdout).hexdigest(), pins=json.loads(result.stdout))
    result = dict(schemaVersion=1, measuredPack='5.2.0', coordinator='4.1.1', baselineRevision='v5.2.0',
        hardware=dict(cpu='Intel Xeon E5-2678 v3', physicalCores=12, logicalProcessors=24, ramNominalGiB=32, gpu='NVIDIA GeForce RTX 2060 SUPER'),
        conditions=dict(framebuffer=[1600,900], renderDistance=8, simulationDistance=8, serverHeapMaxGiB=5,
            clientHeapMaxGiB=6, vsync=False, oneRealClient=True, syntheticServerLoad=True,
            coResidentLiveServerClientAndCpuModel=True, computerUse=False, screenshots=False),
        jfrInterpretation='Execution and native samples are separate. Percentages are statistical stack samples, not CPU wall-time or GPU utilization. Inclusive percentages overlap. ChunkGeneration durations sum concurrent stages, not elapsed wall time.',
        baselinePins=baseline, runs=runs)
    (output/'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(output=str(output/'summary.json'), runs=len(runs),
        acceptedPhases=sum(not p.get('excludedReason') and not p.get('smokeOnly') for r in runs.values() for p in r['summary']['phases']))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('workspace', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT/'verification/performance-2026-10')
    args = parser.parse_args()
    collect(args.workspace.resolve(), args.output.resolve())
