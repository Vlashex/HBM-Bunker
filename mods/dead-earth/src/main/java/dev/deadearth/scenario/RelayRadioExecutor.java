package dev.deadearth.scenario;

import dev.deadearth.site.RelayPlacement;
import dev.deadearth.site.SiteRegistry;
import dev.deadearth.scenario.ScenarioTypes.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Rechecks the selected physical relay after RUNNING has been checkpointed. */
public final class RelayRadioExecutor implements StepExecutor {
    private final MinecraftServer server;
    private final SiteRegistry.Site site;
    private final ScenarioChoice choice;
    public RelayRadioExecutor(MinecraftServer server, SiteRegistry.Site site, ScenarioChoice choice) {
        this.server=server;this.site=site;this.choice=choice;
        if (!choice.radio() || !choice.targetId().equals("relay:"+site.id())) throw new IllegalArgumentException("Radio target mismatch");
    }
    @Override public StepResult execute(UUID scenarioId) {
        if (!server.isSameThread()) throw new IllegalStateException("Radio outside server thread");
        if (server.getPlayerCount()==0 || site.discoverer()==null || site.state()!=SiteRegistry.State.READY
                || !RelayPlacement.intact(server.overworld(),new BlockPos(site.x(),site.y(),site.z())).orElse(false)) return StepResult.UNCERTAIN;
        String message=switch(choice.variant()) {
            case "check_in" -> "контрольный сигнал связи";
            case "survey" -> "запрос на обследование станции";
            case "rally" -> "предложение использовать станцию как точку сбора";
            default -> throw new IllegalStateException("Unknown radio variant");
        };
        server.getPlayerList().broadcastSystemMessage(Component.literal("[DEAD EARTH] Радио: "+message+". Станция "+site.id()
                +"; координаты "+site.x()+" "+site.y()+" "+site.z()+". Событие "+scenarioId),false);
        return StepResult.COMPLETED;
    }
}
