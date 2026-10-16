package dev.deadearth.command;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import dev.deadearth.DeadEarth;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class DeadEarthCommands {
    private static final Gson JSON = new Gson();
    private DeadEarthCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("deadearth").requires(source -> source.hasPermission(2))
                .then(literal("status").executes(context -> report(context.getSource(), () -> status(context.getSource())))
                        .then(argument("scenario", UuidArgument.uuid()).executes(context -> report(context.getSource(),
                                () -> DeadEarth.runtime(context.getSource().getServer())
                                        .findScenario(UuidArgument.getUuid(context, "scenario"))
                                        .orElseThrow(() -> new IllegalArgumentException("Unknown scenario"))))))
                .then(literal("preview").then(argument("request", UuidArgument.uuid()).executes(context -> report(context.getSource(),
                        () -> DeadEarth.runtime(context.getSource().getServer()).preview(UuidArgument.getUuid(context, "request"))))))
                .then(literal("submit").then(argument("request", UuidArgument.uuid()).executes(context -> report(context.getSource(),
                        () -> DeadEarth.runtime(context.getSource().getServer()).submit(UuidArgument.getUuid(context, "request"))))))
                .then(literal("cancel").then(argument("scenario", UuidArgument.uuid())
                        .then(argument("revision", LongArgumentType.longArg(0)).executes(context -> report(context.getSource(),
                                () -> DeadEarth.runtime(context.getSource().getServer()).cancel(
                                        UuidArgument.getUuid(context, "scenario"), LongArgumentType.getLong(context, "revision"))))))));
    }

    private static JsonObject status(CommandSourceStack source) {
        var runtime = DeadEarth.runtime(source.getServer());
        var status = new JsonObject();
        status.addProperty("enabled", runtime.enabled());
        status.addProperty("worldEpoch", runtime.epoch().toString());
        if (!runtime.enabled()) return status;
        var campaign = runtime.campaign().campaign();
        var view = runtime.view();
        status.addProperty("campaignId", campaign.campaignId().toString());
        status.addProperty("activeTicks", campaign.activeTicks());
        status.addProperty("activeDays", campaign.activeTicks() / 24000.0);
        status.addProperty("campaignRevision", campaign.revision());
        status.addProperty("onlinePlayers", view.onlinePlayers());
        status.addProperty("testAnchorLoaded", view.anchorLoaded());
        status.addProperty("catalogId", "warning_beacon_v1");
        var counts = new JsonObject();
        for (var state : dev.deadearth.scenario.ScenarioTypes.State.values())
            counts.addProperty(state.name().toLowerCase(Locale.ROOT),
                    runtime.receipts().stream().filter(r -> r.state() == state).count());
        status.add("scenarios", counts);
        return status;
    }

    private static int report(CommandSourceStack source, Supplier<?> action) {
        try {
            String message = "[DeadEarth] " + JSON.toJson(action.get());
            source.sendSuccess(() -> Component.literal(message), false);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("[DeadEarth] " + error.getMessage()));
            return 0;
        }
    }
}
