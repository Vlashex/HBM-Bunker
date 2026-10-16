package dev.deadearth.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.deadearth.DeadEarth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class SiteCommands {
    private SiteCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return literal("site").then(literal("list").executes(context -> DeadEarthCommands.report(context.getSource(),
                        () -> DeadEarth.runtime(context.getSource().getServer()).sites())))
                .then(literal("preview").then(argument("origin", BlockPosArgument.blockPos())
                        .executes(context -> DeadEarthCommands.report(context.getSource(),
                                () -> DeadEarth.runtime(context.getSource().getServer()).previewRelay(BlockPosArgument.getBlockPos(context, "origin"))))))
                .then(literal("place").then(argument("request", UuidArgument.uuid()).then(argument("origin", BlockPosArgument.blockPos())
                        .executes(context -> DeadEarthCommands.report(context.getSource(),
                                () -> DeadEarth.runtime(context.getSource().getServer()).placeRelay(UuidArgument.getUuid(context, "request"),
                                        BlockPosArgument.getBlockPos(context, "origin")))))));
    }
}
