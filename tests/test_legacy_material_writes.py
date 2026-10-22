import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools import legacy_port

ROOT = Path(__file__).resolve().parents[1]

CHECKS = '''package dev.deadearth.legacy.defense;
public final class MaterialWriteChecks {
  static StackTraceElement f(String name, String method) {
    return new StackTraceElement(name, method, "fixture.java", 1);
  }
  static void check(boolean expected, StackTraceElement... frames) {
    if (MaterialHp.hostileFrames(frames) != expected)
      throw new AssertionError("Incorrect hostile writer classification: " + frames[0]);
  }
  public static void main(String[] args) {
    check(true, f("net.minecraft.world.World", "setBlockState"),
        f("com.dhanantry.scapeandrunparasites.entity.Attacker", "attack"));
    check(true, f("net.minecraft.world.World", "destroyBlock"),
        f("funwayguy.epicsiegemod.ai.Digger", "updateTask"));
    check(false, f("net.minecraft.world.gen.feature.WorldGenMinable", "generate"),
        f("net.minecraftforge.fml.common.registry.GameRegistry", "generateWorld"),
        f("com.dhanantry.scapeandrunparasites.util.PathNavigateClimberStatus", "getPath"));
    check(false, f("othermod.OreGenerator", "generate"),
        f("net.minecraftforge.fml.common.registry.GameRegistry", "generateWorld"),
        f("funwayguy.epicsiegemod.ai.Digger", "updateTask"));
    check(false, f("net.minecraft.server.management.PlayerInteractionManager", "tryHarvestBlock"));
    System.out.println("Material write checks passed: 5");
  }
}'''


class MaterialWriteTests(unittest.TestCase):
    def test_pathfinding_population_is_not_a_hostile_attack(self):
        jdk = Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin')
        if not (jdk / 'javac.exe').is_file():
            self.skipTest('Local JDK17 required')
        cp = os.pathsep.join(map(str, [ROOT / 'work/legacy-coordinator/mod.jar',
            *legacy_port.forge_classpath(ROOT / 'work/storage-server-20261020')]))
        with tempfile.TemporaryDirectory(prefix='material-writes-', dir=ROOT / 'work') as name:
            output = Path(name)
            source = output / 'MaterialWriteChecks.java'
            source.write_text(CHECKS, encoding='utf-8')
            production = ROOT / 'legacy/coordinator/src/main/java/dev/deadearth/legacy/defense/MaterialHp.java'
            compiled = subprocess.run([str(jdk / 'javac.exe'), '--release', '8',
                '-encoding', 'UTF-8', '-proc:none', '-cp', cp, '-d', name,
                str(production), str(source)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            checked = subprocess.run([str(jdk / 'java.exe'), '-cp', name + os.pathsep + cp,
                'dev.deadearth.legacy.defense.MaterialWriteChecks'], capture_output=True, text=True)
            self.assertEqual(checked.returncode, 0, checked.stderr)


if __name__ == '__main__':
    unittest.main()
