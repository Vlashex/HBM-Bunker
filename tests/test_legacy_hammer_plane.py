import os
from pathlib import Path
import subprocess
import unittest

from tools import legacy_port

ROOT = Path(__file__).resolve().parents[1]


class HammerPlaneTests(unittest.TestCase):
    def test_geometry_and_actual_pinned_hbm_transformation(self):
        jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin')
        if not (jdk / 'javac.exe').is_file():
            self.skipTest('Local JDK17 required for bytecode probe')
        fixture = ROOT / 'work/storage-server-20261020'
        cp = os.pathsep.join(str(p) for p in legacy_port.forge_classpath(fixture))
        output = ROOT / 'work/hammer-aim-research/test-classes'
        output.mkdir(parents=True, exist_ok=True)
        base = ROOT / 'legacy/coordinator/src/main/java/dev/deadearth/legacy'
        sources = [base / 'gameplay/HammerPlane.java', base / 'core/HammerPlaneTransformer.java',
                   base / 'core/LegacyTransformer.java', ROOT / 'legacy/coordinator/src/test/java/dev/deadearth/legacy/gameplay/HammerPlaneChecks.java']
        command = [str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-proc:none',
                   '-classpath', cp, '-d', str(output), *map(str, sources)]
        subprocess.run(command, check=True, capture_output=True, text=True)
        result = subprocess.run([str(jdk / 'java.exe'), '-Xverify:all', '-cp', str(output) + os.pathsep + cp,
                                 'dev.deadearth.legacy.gameplay.HammerPlaneChecks',
                                 str(ROOT / '.cache/legacy/NTM-CE-1.12.2-2.6.1.0.jar')],
                                check=True, capture_output=True, text=True)
        self.assertIn('Hammer plane checks passed:', result.stdout)


if __name__ == '__main__':
    unittest.main()
