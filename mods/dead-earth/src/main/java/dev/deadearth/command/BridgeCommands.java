package dev.deadearth.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.deadearth.DeadEarth;
import net.minecraft.commands.CommandSourceStack;
import static net.minecraft.commands.Commands.literal;

public final class BridgeCommands {
    private BridgeCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return literal("bridge")
                .then(literal("start").executes(c -> DeadEarthCommands.report(c.getSource(), () -> DeadEarth.runtime(c.getSource().getServer()).bridge().start())))
                .then(literal("status").executes(c -> DeadEarthCommands.report(c.getSource(), () -> DeadEarth.runtime(c.getSource().getServer()).bridge().status())))
                .then(literal("stop").executes(c -> DeadEarthCommands.report(c.getSource(), () -> {
                    var bridge = DeadEarth.runtime(c.getSource().getServer()).bridge(); bridge.close(); return bridge.status();
                })));
    }
}
