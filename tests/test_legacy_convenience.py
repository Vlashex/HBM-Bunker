import os
from pathlib import Path
import subprocess
import unittest

from tools import legacy_port

ROOT = Path(__file__).resolve().parents[1]


class ConvenienceTests(unittest.TestCase):
    def test_native_stack_hook_and_backpack_side_guard(self):
        jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin')
        fixture = ROOT / 'work/storage-server-20261020'
        addon = ROOT / '.cache/legacy/V0ids-Smart-Backpacks-1.0.6.jar'
        if not (jdk / 'javac.exe').is_file() or not fixture.is_dir() or not addon.is_file():
            self.skipTest('Local JDK17, Forge2860 and pinned backpack fixture required')
        cp = os.pathsep.join(map(str, legacy_port.forge_classpath(fixture)))
        output = ROOT / 'work/convenience-checks/classes'
        output.mkdir(parents=True, exist_ok=True)
        main = ROOT / 'legacy/coordinator/src/main/java/dev/deadearth/legacy'
        sources = [main / n for n in ('Reflect.java', 'core/LegacyTransformer.java',
                   'core/ConvenienceTransformer.java', 'core/HammerPlaneTransformer.java',
                   'gameplay/WaterStacks.java')]
        # The local spawn-balance prototype is a separate, unreleased working change.
        spawn = main / 'core/SpawnCapTransformer.java'
        if spawn.exists():
            sources.append(spawn)
        tests = ROOT / 'legacy/coordinator/src/test/java/dev/deadearth/legacy/gameplay'
        sources += [tests / 'WaterStacksChecks.java', tests / 'ConvenienceChecks.java']
        subprocess.run([str(jdk / 'javac.exe'), '--release', '8', '-encoding', 'UTF-8',
                        '-proc:none', '-cp', cp, '-d', str(output), *map(str, sources)],
                       check=True, capture_output=True, text=True)
        for name, args in [('WaterStacksChecks', []), ('ConvenienceChecks', [str(addon)])]:
            with self.subTest(name=name):
                result = subprocess.run([str(jdk / 'java.exe'), '-Xverify:all', '-cp',
                                         str(output) + os.pathsep + cp,
                                         'dev.deadearth.legacy.gameplay.' + name, *args],
                                        check=True, capture_output=True, text=True)
                self.assertIn('checks:', result.stdout)


if __name__ == '__main__':
    unittest.main()
