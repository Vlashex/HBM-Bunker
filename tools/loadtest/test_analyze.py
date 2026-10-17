"""Synthetic time-window regression; does not start Minecraft or change test worlds."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from analyze import analyze


class AnalyzerWindows(unittest.TestCase):
    def test_cold_includes_queued_setup_and_dynamic_is_not_static_drift(self):
        phases=[dict(name='chunks_cold_64',startMs=1000,measurementStartMs=10000,endMs=13000),
                dict(name='dynamic_test',cameraMode='planned_dynamic',startMs=20000,measurementStartMs=20000,endMs=27000),
                dict(name='static_drift',requireExpectedCamera=True,startMs=30000,measurementStartMs=30000,endMs=37000)]
        keys=('fullChunks','entityTickingChunks','tickingChunks','tickingBlockEntities','entities','mobs','mobsWithAi','mobsWithTarget','items','heapUsed','freePhysicalBytes','zones','loaders','gcMillis','gcCount','processCpuNanos')
        server=[];client=[]
        for name,timestamps in [('chunks_cold_64',(2000,11000)),('dynamic_test',(24000,25000)),('static_drift',(34000,35000))]:
            for index,stamp in enumerate(timestamps):
                row=dict.fromkeys(keys,1);row.update(phase=name,wallTimeMs=stamp,ticks=20,windowNanos=1_000_000_000,tickNanos=[1_000_000]*20,vanillaTickNanos=[1_000_000]*20,jobsRemaining=10 if stamp==2000 else 0,entityTypes={})
                if stamp==2000:row['phase']='previous_window_label'
                server.append(row)
                client.append(dict(type='window',phase=name,timestampMs=stamp,measurementEligible=True,windowIconified=False,windowFocused=True,
                                   frameIntervalsNanos=[10_000_000]*100,shader=dict(available=True,enabled=True,configuredEnabled=True,fallback=False,error=False),
                                   framebufferWidth=1600,framebufferHeight=900,camera=dict(x=2+index*5,y=102.62,z=-35.5,yaw=index*30,pitch=15)))
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary)
            (folder/'phases.json').write_text(json.dumps(phases),encoding='utf-8')
            for filename,data in [('server-metrics.jsonl',server),('client-metrics.jsonl',client)]:
                (folder/filename).write_text('\n'.join(json.dumps(row) for row in data),encoding='utf-8')
            with contextlib.redirect_stdout(io.StringIO()):analyze(folder)
            result={row['name']:row for row in json.loads((folder/'summary.json').read_text())['phases']}
        self.assertEqual(result['chunks_cold_64']['startMs'],1000)
        self.assertEqual(result['chunks_cold_64']['serverWindows'],2)
        self.assertEqual(result['chunks_cold_64']['measurementKind'],'forcing_generation_transition')
        self.assertIsNone(result['dynamic_test']['excludedReason'])
        self.assertEqual(result['dynamic_test']['observedCameraSpan']['yaw'],30)
        self.assertIn('Camera moved',result['static_drift']['excludedReason'])


if __name__=='__main__':unittest.main()
