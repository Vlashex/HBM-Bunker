package dev.deadearth.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import dev.deadearth.DeadEarth;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.scenario.ScenarioStore;
import dev.deadearth.scenario.ScenarioTypes.*;
import dev.deadearth.scenario.WarningBeaconExecutor;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth")
@PrefixGameTestTemplate(false)
public final class FoundationGameTests {
    @GameTest(template = "empty")
    public static void commandsRequireOperator(GameTestHelper helper) throws CommandSyntaxException {
        var server = helper.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var source = server.createCommandSourceStack();
        boolean denied = false;
        try { dispatcher.execute("deadearth status", source.withPermission(0)); }
        catch (CommandSyntaxException expected) { denied = true; }
        helper.assertTrue(denied, "Non-operator received coordinator access");
        helper.assertTrue(dispatcher.execute("deadearth status", source.withPermission(2)) > 0,
                "Operator could not inspect coordinator");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savedDataRoundTripPreservesRequest(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        UUID campaign = UUID.randomUUID(), epoch = UUID.randomUUID(), request = UUID.randomUUID();
        var store = ScenarioStore.create(campaign);
        var receipt = store.engine().submit(new Proposal(request, epoch, "test_anchor", 200, 1200),
                new WorldView(epoch, 0, 1, true));
        SavedDataAccess.checkpoint(helper.getLevel(), "dead_earth_gametest_roundtrip", store);
        var path = server.getWorldPath(LevelResource.ROOT).resolve("data/dead_earth_gametest_roundtrip.dat");
        var raw = NbtIo.readCompressed(path, NbtAccounter.create(8 * 1024 * 1024));
        var restored = ScenarioStore.load(campaign, raw.getCompound("data"), server.registryAccess());
        helper.assertTrue(restored.enabled(), "Checkpoint could not be loaded");
        helper.assertTrue(restored.engine().findRequest(request).orElseThrow().equals(receipt), "Receipt changed on disk");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void warningRunsOnServerThread(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        try (var client = attach(helper)) {
            var executor = new WarningBeaconExecutor(server);
            var id = UUID.randomUUID();
            helper.assertTrue(executor.execute(id) == StepResult.COMPLETED, "Signal not acknowledged");
            helper.assertTrue(client.messages().stream().filter(s -> s.contains(id.toString())).count() == 1,
                    "Expected exactly one chat packet");
            boolean rejected = CompletableFuture.runAsync(() -> executor.execute(UUID.randomUUID()))
                    .handle((result, error) -> error != null && error.getCause() instanceof IllegalStateException).join();
            helper.assertTrue(rejected, "World effect allowed from worker thread");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void duplicateSubmitProducesOneSignal(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var client = attach(helper);
        try {
            helper.getLevel().getChunkAt(helper.getLevel().getSharedSpawnPos());
            var runtime = DeadEarth.runtime(server);
            var request = UUID.randomUUID();
            var first = runtime.submit(request);
            helper.assertTrue(first.equals(runtime.submit(request)), "Duplicate request changed receipt");
            var cancelled = runtime.submit(UUID.randomUUID());
            helper.assertTrue(runtime.cancel(cancelled.scenarioId(), cancelled.revision()).state() == State.CANCELLED,
                    "Pending scenario was not cancelled");
            helper.runAfterDelay(210, () -> {
                try {
                    var messages = client.messages();
                    helper.assertTrue(messages.stream().filter(s -> s.contains(first.scenarioId().toString())).count() == 1,
                            "Duplicate or missing signal");
                    helper.assertTrue(messages.stream().noneMatch(s -> s.contains(cancelled.scenarioId().toString())),
                            "Cancelled signal was sent");
                    helper.assertTrue(runtime.findScenario(first.scenarioId()).orElseThrow().state() == State.COMPLETED,
                            "Successful signal was not persisted as completed");
                    helper.succeed();
                } finally { client.close(); }
            });
        } catch (RuntimeException | Error error) {
            client.close();
            throw error;
        }
    }

    @GameTest(template = "empty")
    public static void clockDoesNotFollowWorldTime(GameTestHelper helper) {
        var level = helper.getLevel();
        var runtime = DeadEarth.runtime(level.getServer());
        var client = attach(helper);
        long originalDayTime = level.getDayTime();
        long before = runtime.campaign().campaign().activeTicks();
        level.setDayTime(originalDayTime + 72000);
        helper.runAfterDelay(3, () -> {
            try {
                long elapsed = runtime.campaign().campaign().activeTicks() - before;
                helper.assertTrue(elapsed >= 1 && elapsed <= 5, "World time skip changed active campaign ticks");
                helper.succeed();
            } finally { level.setDayTime(originalDayTime); client.close(); }
        });
    }

    @GameTest(template = "empty", batch = "offline")
    public static void offlinePausesClock(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(server.getPlayerCount() == 0, "Offline test must run without players");
        var runtime = DeadEarth.runtime(server);
        long before = runtime.campaign().campaign().activeTicks();
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(runtime.campaign().campaign().activeTicks() == before, "Clock advanced while offline");
            helper.succeed();
        });
    }

    static AttachedClient attach(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(UUID.randomUUID(), "DEProbe" + UUID.randomUUID().toString().substring(0, 6));
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return new AttachedClient(player, channel);
    }

    record AttachedClient(ServerPlayer player, EmbeddedChannel channel) implements AutoCloseable {
        List<String> messages() {
            var messages = new ArrayList<String>();
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet instanceof ClientboundSystemChatPacket chat) messages.add(chat.content().getString());
            }
            return messages;
        }
        @Override public void close() {
            player.getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }
}
