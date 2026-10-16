package dev.deadearth.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.deadearth.DeadEarth;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import static net.minecraft.commands.Commands.literal;

public final class ClimateCommands {
    private ClimateCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return literal("climate")
                .then(literal("status").executes(context -> DeadEarthCommands.report(context.getSource(),
                        () -> Map.of("mode","forecast_only","climate",DeadEarth.runtime(context.getSource().getServer()).climateStatus()))))
                .then(literal("forecast").executes(context -> DeadEarthCommands.report(context.getSource(),
                        () -> Map.of("mode","forecast_only","windows",DeadEarth.runtime(context.getSource().getServer()).climateForecast()))));
    }
}
