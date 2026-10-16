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
                        () -> Map.of("mode","active","climate",DeadEarth.runtime(context.getSource().getServer()).climateStatus(),
                                "effectiveSeason",DeadEarth.runtime(context.getSource().getServer()).effectiveSeason(),
                                "modifiers",dev.deadearth.climate.SeasonPolicy.forPhase(DeadEarth.runtime(context.getSource().getServer()).effectiveSeason())))))
                .then(literal("test-phase").requires(source->Boolean.getBoolean("deadearth.testControls"))
                    .then(testPhase("summer",dev.deadearth.climate.ClimateCycle.Phase.HEAT))
                    .then(testPhase("winter",dev.deadearth.climate.ClimateCycle.Phase.ASH_WINTER))
                    .then(testPhase("calm",dev.deadearth.climate.ClimateCycle.Phase.CALM))
                    .then(testPhase("resume",null)))
                .then(literal("forecast").executes(context -> DeadEarthCommands.report(context.getSource(),
                        () -> Map.of("mode","active","windows",DeadEarth.runtime(context.getSource().getServer()).climateForecast()))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> testPhase(String name,dev.deadearth.climate.ClimateCycle.Phase phase) {
        return literal(name).executes(context->{
            DeadEarth.runtime(context.getSource().getServer()).testSeason(phase);
            context.getSource().sendSuccess(()->net.minecraft.network.chat.Component.literal("Dead Earth test season: "+name),false);return 1;
        });
    }
}
