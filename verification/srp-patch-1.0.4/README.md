# SRP1.0.4: continuous wasteland surface

The surface search passes vanilla logs/leaves, huge mushroom blocks and natural
plants (including vines, cactus and saplings), reaching the soil underneath.
Transparent cover immediately above the native height map is included. Trunks
remain, while non-HBM vegetation removal uses the remaining write budget after
mandatory soil coating. A dense canopy cannot cancel a complete chunk plan.

Every `hbm:*` cell is preserved exactly, including ores, native geology and
plants. An HBM obstruction prevents only unsafe local crater displacement.
Soil around/above it still receives safe coating independently of underground
architecture, unknown blocks and deposits. Native ore generation is unchanged.

Saved, explicitly unpopulated/unmarked edges stay eligible for their FIRST
population pass. Existing populated/unknown saves remain protected. Marker1
means painted, marker2 protects an old save; future nonzero values are retained
exactly. Genuine Forge Save/Load event objects verify durable protection after
roundtrip, without invoking unrelated mod event listeners. No existing player
terrain is repainted and no player world was reset.

Final verification:354657 pure worldgen assertions;38 Python checks;290 native
checks in15 groups;47 restart checks. The initial stale-height-map test fixture
placed its obstacle inside the newly expanded search band; it was corrected to
keep the obstacle outside that band and assert actual unsafe-rim rejection.
The native observer independently includes soil under vegetation, sampled75
fresh chunks across3 regions, found0 unconverted soil islands and retained all
411 observed HBM cells. Scheduled HBM ore callbacks were not observed by this
synchronous pre-pass observer: their generation is not claimed tested here.
The full pack, four-click sieves and bridge/admin regression groups passed.
The genuine separate JVM restart preserved physical sentinels and saves.

No performance/stress session, automated OpenGL screenshot or fresh model
inference was run. Installation waits for the user's single client to close;
the current playable instance remains1.0.3 until that explicit step.
