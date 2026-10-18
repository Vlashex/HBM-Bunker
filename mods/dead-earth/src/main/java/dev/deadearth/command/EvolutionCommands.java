package dev.deadearth.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.deadearth.DeadEarth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import static net.minecraft.commands.Commands.*;

/** Fixed human controls; no corresponding model/MCP write tool. */
public final class EvolutionCommands {
    private EvolutionCommands(){}
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        var delta=argument("delta",IntegerArgumentType.integer(-1,1)).executes(c->DeadEarthCommands.report(c.getSource(),()->
            DeadEarth.runtime(c.getSource().getServer()).shiftEvolution(UuidArgument.getUuid(c,"campaign"),UuidArgument.getUuid(c,"epoch"),LongArgumentType.getLong(c,"revision"),UuidArgument.getUuid(c,"request"),IntegerArgumentType.getInteger(c,"delta"))));
        var request=argument("request",UuidArgument.uuid()).then(delta);
        var revision=argument("revision",LongArgumentType.longArg(0)).then(request);
        var epoch=argument("epoch",UuidArgument.uuid()).then(revision);
        var campaign=argument("campaign",UuidArgument.uuid()).then(epoch);
        return literal("evolution").requires(s->s.hasPermission(4))
            .then(literal("status").executes(c->DeadEarthCommands.report(c.getSource(),()->DeadEarth.runtime(c.getSource().getServer()).evolutionStatus())))
            .then(literal("shift").then(campaign));
    }
}
