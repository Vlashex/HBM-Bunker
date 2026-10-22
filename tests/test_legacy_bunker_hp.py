from pathlib import Path
import os
import subprocess
import unittest
from tools import legacy_port

ROOT = Path(__file__).resolve().parents[1]


class BunkerHpTests(unittest.TestCase):
    def test_new_tiers_and_saved_damage_migration(self):
        jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin')
        if not (jdk / 'javac.exe').is_file():
            self.skipTest('Local Java compiler required')
        cp = os.pathsep.join(map(str, [ROOT / 'work/legacy-coordinator/mod.jar',
            *legacy_port.forge_classpath(ROOT / 'work/storage-server-20261020')]))
        output = ROOT / 'work/bunker-hp-checks'
        output.mkdir(exist_ok=True)
        sources = [ROOT / 'legacy/coordinator/src/main/java/dev/deadearth/legacy/defense/DefenseDamageStore.java',
                   ROOT / 'legacy/coordinator/src/test/java/dev/deadearth/legacy/defense/DefenseChecks.java']
        subprocess.run([str(jdk / 'javac.exe'), '--release', '8', '-encoding', 'UTF-8',
            '-proc:none', '-cp', cp, '-d', str(output), *map(str, sources)], check=True, capture_output=True)
        result = subprocess.run([str(jdk / 'java.exe'), '-Djava.io.tmpdir=' + str(output), '-cp', str(output) + os.pathsep + cp,
            'dev.deadearth.legacy.defense.DefenseChecks'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
