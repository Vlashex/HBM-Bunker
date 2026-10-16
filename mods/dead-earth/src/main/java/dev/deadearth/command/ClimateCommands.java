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
                .then(literal("test-coating").requires(source->Boolean.getBoolean("deadearth.testControls"))
                    .then(net.minecraft.commands.Commands.argument("x",com.mojang.brigadier.arguments.IntegerArgumentType.integer(-29999900,29999900))
                    .then(net.minecraft.commands.Commands.argument("z",com.mojang.brigadier.arguments.IntegerArgumentType.integer(-29999900,29999900))
                    .then(net.minecraft.commands.Commands.argument("radius",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,12))
                    .executes(context->{
                        int x=com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context,"x"),z=com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context,"z");
                        int radius=com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context,"radius");
                        var level=context.getSource().getServer().overworld();int queued=0;
                        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)
                            if(dev.deadearth.climate.WinterCoating.loaded(level,x+dx,z+dz,dev.deadearth.climate.WinterCoating.maximumSideReach()+1)) {
                                dev.deadearth.climate.WinterCoating.prioritizeTestColumn(level,x+dx,z+dz);queued++;
                            }
                        final int count=queued;
                        context.getSource().sendSuccess(()->net.minecraft.network.chat.Component.literal("Dead Earth queued test columns: "+count+" center="+dev.deadearth.climate.WinterCoating.profile(level,x,z)),false);return count;
                    })))))
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
