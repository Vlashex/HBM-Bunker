package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import dev.deadearth.site.RelayPlacement;
import dev.deadearth.site.SiteRegistry;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import java.util.function.Consumer;

@GameTestHolder("dead_earth")
@PrefixGameTestTemplate(false)
public final class ExpeditionGameTests {
    @GameTest(template = "empty", batch = "relay", timeoutTicks = 100)
    public static void confirmedDeathIsRecordedOnce(GameTestHelper helper) {
        var client = FoundationGameTests.attach(helper);
        client.player().setHealth(0);
        client.player().die(client.player().damageSources().generic());
        helper.runAfterDelay(3, () -> {
            try {
                long count = DeadEarth.runtime(helper.getLevel().getServer()).snapshot().observations().history().stream()
                        .filter(e -> e.kind() == dev.deadearth.observation.ObservationLog.Kind.DEATH && e.player().equals(client.player().getUUID())).count();
                helper.assertTrue(count == 1, "Confirmed death missing or duplicated"); helper.succeed();
            } finally { client.close(); }
        });
    }
    @GameTest(template = "empty", batch = "relay", timeoutTicks = 100)
    public static void cancelledDeathDoesNotBecomeObservedFact(GameTestHelper helper) {
        var client = FoundationGameTests.attach(helper);
        Consumer<LivingDeathEvent> cancel = e -> { if (e.getEntity() == client.player()) e.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, false, LivingDeathEvent.class, cancel);
        var event = new LivingDeathEvent(client.player(), client.player().damageSources().generic());
        NeoForge.EVENT_BUS.post(event);
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(event.isCanceled(), "Death fixture was not cancelled");
                helper.assertTrue(DeadEarth.runtime(helper.getLevel().getServer()).snapshot().observations().history().stream()
                        .noneMatch(e -> e.kind() == dev.deadearth.observation.ObservationLog.Kind.DEATH && e.player().equals(client.player().getUUID())),
                        "Cancelled death was recorded as a fact");
                helper.succeed();
            } finally { NeoForge.EVENT_BUS.unregister(cancel); client.close(); }
        });
    }
    @GameTest(template = "empty", batch = "relay")
    public static void placementDoesNotLoadNeighbourChunks(GameTestHelper helper) {
        var level = helper.getLevel(); var origin = new BlockPos(3520, 96, 3520); prepare(level, origin);
        var neighbour = origin.west();
        helper.assertTrue(!level.hasChunkAt(neighbour), "Neighbour fixture already loaded");
        RelayPlacement.place(level, origin);
        helper.assertTrue(!level.hasChunkAt(neighbour), "Placement loaded a neighbouring chunk");
        helper.succeed();
    }
    @GameTest(template = "empty", batch = "relay", timeoutTicks = 260)
    public static void incompleteRelayCannotBeDiscoveredAsReady(GameTestHelper helper) {
        var level = helper.getLevel(); var origin = new BlockPos(3328, 96, 3200); prepare(level, origin);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        var runtime = DeadEarth.runtime(level.getServer()); var site = runtime.placeRelay(UUID.randomUUID(), origin);
        helper.assertTrue(level.setBlock(origin.offset(4, 5, 4), Blocks.AIR.defaultBlockState(), 2 | 16), "Missing-block fixture did not change the station");
        helper.assertTrue(runtime.sites().stream().filter(s -> s.id().equals(site.id())).findFirst().orElseThrow().state() == SiteRegistry.State.UNCERTAIN,
                "Loaded incomplete station was not rejected immediately");
        var client = FoundationGameTests.attach(helper);
        client.player().teleportTo(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 3.5);
        helper.runAfterDelay(210, () -> {
            try {
                helper.assertTrue(runtime.sites().stream().filter(s -> s.id().equals(site.id())).findFirst().orElseThrow().state() == SiteRegistry.State.UNCERTAIN,
                        "Incomplete physical site remained READY");
                helper.assertTrue(runtime.snapshot().discoveredSites().stream().noneMatch(s -> s.id().equals(site.id())), "Phantom relay was discovered");
                helper.succeed();
            } finally { client.close(); level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false); }
        });
    }
    @GameTest(template = "empty", batch = "relay")
    public static void occupiedVolumeIsNeverOverwritten(GameTestHelper helper) {
        var level = helper.getLevel(); var origin = new BlockPos(3200, 96, 3200); prepare(level, origin);
        var occupied = origin.offset(1, 1, 1); level.setBlockAndUpdate(occupied, Blocks.GOLD_BLOCK.defaultBlockState());
        var preview = RelayPlacement.preview(level, origin);
        helper.assertTrue(!preview.allowed() && preview.reason().equals("occupied_volume"), "Occupied volume was accepted");
        boolean refused = false;
        try { RelayPlacement.place(level, origin); } catch (IllegalStateException expected) { refused = true; }
        helper.assertTrue(refused && level.getBlockState(occupied).is(Blocks.GOLD_BLOCK), "Existing block changed");
        helper.assertTrue(level.getBlockState(origin).isAir(), "Rejected placement partially built its floor");
        helper.succeed();
    }
    @GameTest(template = "empty", batch = "relay")
    public static void previewDoesNotLoadDistantChunks(GameTestHelper helper) {
        var level = helper.getLevel(); var origin = new BlockPos(3000000, 96, 3000000);
        helper.assertTrue(!level.hasChunkAt(origin), "Fixture target was already loaded");
        var preview = RelayPlacement.preview(level, origin);
        helper.assertTrue(!preview.allowed() && preview.reason().equals("chunks_not_loaded"), "Distant placement was accepted");
        helper.assertTrue(!level.hasChunkAt(origin), "Preview loaded distant chunk"); helper.succeed();
    }
    @GameTest(template = "empty", batch = "relay", timeoutTicks = 260)
    public static void relayPlacementAndDiscoveryAreDeduplicated(GameTestHelper helper) {
        var level = helper.getLevel(); var origin = new BlockPos(3248, 96, 3200); prepare(level, origin);
        var runtime = DeadEarth.runtime(level.getServer()); var request = UUID.randomUUID();
        var site = runtime.placeRelay(request, origin);
        helper.assertTrue(site.state() == SiteRegistry.State.READY, "Relay not marked ready");
        helper.assertTrue(runtime.placeRelay(request, origin).equals(site), "Duplicate request created another relay");
        helper.assertTrue(level.getBlockState(origin).is(Blocks.CRACKED_STONE_BRICKS), "Floor missing");
        helper.assertTrue(level.getBlockState(origin.offset(4, 5, 4)).is(Blocks.REDSTONE_LAMP), "Antenna missing");
        helper.assertTrue(runtime.snapshot().discoveredSites().stream().noneMatch(s -> s.id().equals(site.id())), "Unknown site leaked to observations");
        var client = FoundationGameTests.attach(helper);
        client.player().teleportTo(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 3.5);
        helper.runAfterDelay(210, () -> {
            try {
                var snapshot = runtime.snapshot();
                helper.assertTrue(snapshot.discoveredSites().stream().filter(s -> s.id().equals(site.id())).count() == 1, "Site discovery missing or duplicated");
                helper.assertTrue(snapshot.observations().history().stream().filter(e -> e.subject().equals(site.id().toString())).count() == 1,
                        "Discovery history missing or duplicated");
                helper.assertTrue(client.messages().stream().filter(s -> s.contains("станция связи")).count() == 1, "Discovery signal missing or duplicated");
                helper.succeed();
            } finally { client.close(); }
        });
    }
    private static void prepare(ServerLevel level, BlockPos origin) {
        for (int x : new int[] {0, 8}) for (int z : new int[] {0, 8}) level.getChunkAt(origin.offset(x, 0, z));
        for (var pos : BlockPos.betweenClosed(origin, origin.offset(8, 5, 8))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16);
        for (var pos : BlockPos.betweenClosed(origin.below(), origin.offset(8, -1, 8))) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2 | 16);
    }
}
