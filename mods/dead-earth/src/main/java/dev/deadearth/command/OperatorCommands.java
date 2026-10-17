package dev.deadearth.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.deadearth.DeadEarth;
import dev.deadearth.director.CinematicDirector;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Human operator surface. These commands are deliberately absent from bridge/MCP tools. */
public final class OperatorCommands {
    private OperatorCommands() {}
    private static CinematicDirector director(CommandContext<CommandSourceStack> context){return DeadEarth.runtime(context.getSource().getServer()).cinematics();}
    private static UUID id(CommandContext<CommandSourceStack> context,String name){return UuidArgument.getUuid(context,name);}
    private static String word(CommandContext<CommandSourceStack> context,String name){return StringArgumentType.getString(context,name);}
    private static int report(CommandContext<CommandSourceStack> context,Supplier<?> operation) {
        return DeadEarthCommands.report(context.getSource(),()->{
            try{return operation.get();}
            catch(RuntimeException failed){return Map.of("accepted",false,"code","operator_error");}
        });
    }
    private static LiteralArgumentBuilder<CommandSourceStack> selection(boolean run) {
        var zone=argument("zone",UuidArgument.uuid()).executes(c->report(c,()->run
            ?director(c).operatorRun(id(c,"epoch"),word(c,"revision"),id(c,"request"),word(c,"event"),id(c,"zone").toString())
            :director(c).operatorPreview(id(c,"epoch"),word(c,"revision"),word(c,"event"),id(c,"zone").toString())));
        var event=argument("event",StringArgumentType.word()).then(zone);
        var revision=argument("revision",StringArgumentType.word());
        if(run)revision.then(argument("request",UuidArgument.uuid()).then(event));else revision.then(event);
        return literal(run?"run":"preview").then(argument("epoch",UuidArgument.uuid()).then(revision));
    }
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        var branch=literal("operator").requires(source->source.hasPermission(4));
        branch.then(literal("status").executes(c->report(c,()->director(c).operatorStatus())));
        branch.then(selection(false));branch.then(selection(true));
        var cancel=argument("operation",UuidArgument.uuid()).executes(c->report(c,()->director(c).operatorCancel(id(c,"epoch"),id(c,"operation"))));
        branch.then(literal("cancel").then(argument("epoch",UuidArgument.uuid()).then(cancel)));
        var validate=argument("stage",UuidArgument.uuid()).executes(c->report(c,()->director(c).operatorValidate(id(c,"epoch"),id(c,"stage"))));
        branch.then(literal("validate").then(argument("epoch",UuidArgument.uuid()).then(validate)));
        var apply=argument("stage",UuidArgument.uuid()).executes(c->report(c,()->director(c).operatorApply(id(c,"epoch"),word(c,"revision"),id(c,"stage"))));
        branch.then(literal("apply").then(argument("epoch",UuidArgument.uuid()).then(argument("revision",StringArgumentType.word()).then(apply))));
        return branch;
    }
}
