# SRP 1.0.2 localization repair

The user saw literal `tile.dead_earth.compact_sieve_*` keys in the running
1.0.1 client. The active installed JAR matched the prior artifact exactly.
Its lowercase language files had correct keys, but its missing pack.mcmeta
made Forge synthesize format 2 and select Minecraft LegacyV2Adapter. That
adapter requests uppercase legacy language filenames which are absent.

The only functional fix is standard root pack.mcmeta with pack_format 3.
The entry point, mod metadata and manifest also carry the new version.
Jar comparison confirms all 137 other gameplay classes are byte-identical;
the entry point differs only in the version string. No world/sieve schema,
recipes, configs, progress rules or saved game data changed.

Native headless verification uses the cached signed Minecraft 1.12.2 JAR,
Forge 2860 FMLFileResourcePack, native metadata/LegacyV2Adapter selection,
SimpleReloadableResourceManager and Locale, running on the actual profile's
Java 8. It starts no game/display and injects no translations. The old JAR
resolves 0/193 names; the new one resolves 193/193 in Russian and English.
Reports include actual resolved example names and artifact hashes. The
helper is tools/legacy_locale_probe.py, with --expect legacy-missing for old.

38 existing legacy Python checks passed. Archives built successfully.
No additional client/server, performance test or GPU/GL verification ran.
After the user saved and closed the client, the patch was installed into the
owned server and both prepared client profiles, with previous own JARs
backed up. Exactly one client was started and completed the Forge handshake
with dead_earth@1.0.2; the dedicated server confirmed its player joined.
The existing server world and singleplayer saves were preserved. Admin
HTTP on port 8766 responds, console reports ready, and a real read-only
list command completed through the admin HTTP endpoint and RCON. No
automated screenshot/UI text inspection or performance test ran.
