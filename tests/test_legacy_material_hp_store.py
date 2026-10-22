import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools import legacy_port

ROOT = Path(__file__).resolve().parents[1]


class MaterialHpStoreTests(unittest.TestCase):
    def test_persistent_chunk_hp_with_real_json_checkpoints(self):
        jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin')
        if not (jdk / 'javac.exe').is_file():
            self.skipTest('Local JDK17 required for material HP checks')
        dependencies = [ROOT / 'work/legacy-coordinator/mod.jar',
                        *legacy_port.forge_classpath(ROOT / 'work/storage-server-20261020')]
        self.assertTrue(dependencies[0].is_file(), 'Compile the current coordinator first')
        classpath = os.pathsep.join(map(str, dependencies))
        base = ROOT / 'legacy/coordinator/src'
        sources = [base / 'test/java/dev/deadearth/legacy/defense/MaterialDamageChecks.java']
        store = base / 'main/java/dev/deadearth/legacy/defense/MaterialDamageStore.java'
        if store.is_file():
            sources.append(store)
        work = ROOT / 'work'
        with tempfile.TemporaryDirectory(prefix='material-hp-checks-', dir=work) as temporary:
            output = Path(temporary)
            self.assertEqual(output.resolve().parent, work.resolve())
            command = [str(jdk / 'javac.exe'), '--release', '8', '-encoding', 'UTF-8',
                       '-proc:none', '-classpath', classpath, '-d', str(output), *map(str, sources)]
            compiled = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            checked = subprocess.run(
                [str(jdk / 'java.exe'), '-Xverify:all', '-cp', str(output) + os.pathsep + classpath,
                 'dev.deadearth.legacy.defense.MaterialDamageChecks', str(output / 'fixtures')],
                capture_output=True, text=True)
            self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
            self.assertIn('Material damage checks passed:', checked.stdout)


if __name__ == '__main__':
    unittest.main()
