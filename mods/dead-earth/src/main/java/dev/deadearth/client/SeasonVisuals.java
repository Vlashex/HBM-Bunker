package dev.deadearth.client;

import dev.deadearth.climate.ClimateCycle;
import dev.deadearth.climate.SeasonNetwork;
import dev.deadearth.climate.SeasonState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid="dead_earth",value=Dist.CLIENT)
public final class SeasonVisuals {
    private static float winter,heat;
    private SeasonVisuals() {}
    public static void receive(SeasonNetwork.State state) {
        SeasonState.update(ClimateCycle.Phase.values()[state.phase()],state.pressure());
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        winter=heat=0;SeasonState.reset();
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if (mc.level==null || mc.player==null || !mc.level.dimension().equals(Level.OVERWORLD)) {winter=heat=0;return;}
        winter=approach(winter,SeasonState.phase()==ClimateCycle.Phase.ASH_WINTER?1:0);
        heat=approach(heat,SeasonState.phase()==ClimateCycle.Phase.HEAT?1:0);
        if (mc.isPaused() || !mc.level.canSeeSky(mc.player.blockPosition()) || mc.level.getGameTime()%3!=0) return;
        var random=mc.level.random;
        for(int i=0;i<3;i++) {
            double x=mc.player.getX()+random.nextDouble()*24-12,y=mc.player.getY()+random.nextDouble()*10,z=mc.player.getZ()+random.nextDouble()*24-12;
            if (winter>.2f) mc.level.addParticle(ParticleTypes.WHITE_ASH,x,y,z,-.025,-.015,0);
            if (heat>.2f) mc.level.addParticle(ParticleTypes.SMOKE,x,y,z,0,.015,0);
        }
    }
    @SubscribeEvent public static void color(ViewportEvent.ComputeFogColor event) {
        var mc=Minecraft.getInstance();
        if (mc.level==null || !mc.level.dimension().equals(Level.OVERWORLD) || event.getCamera().getFluidInCamera()!=FogType.NONE) return;
        float amount=winter*.7f+heat*.25f;
        event.setRed(event.getRed()*(1-amount)+winter*.30f+heat*.24f);
        event.setGreen(event.getGreen()*(1-amount)+winter*.34f+heat*.15f);
        event.setBlue(event.getBlue()*(1-amount)+winter*.40f+heat*.06f);
    }
    @SubscribeEvent public static void fog(ViewportEvent.RenderFog event) {
        var mc=Minecraft.getInstance();
        if (mc.level==null || mc.player==null || !mc.level.dimension().equals(Level.OVERWORLD)
                || event.getType()!=FogType.NONE || !mc.level.canSeeSky(mc.player.blockPosition())) return;
        if (winter>.05f || heat>.05f) {
            float far=event.getFarPlaneDistance();
            float target=Math.min(far,winter>.05f?72:160);
            event.setFarPlaneDistance(far+(target-far)*Math.max(winter,heat));
            event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(),event.getFarPlaneDistance()*.25f));
            event.setCanceled(true);
        }
    }
    private static float approach(float current,float target) { return current+(target-current)*.015f; }
}
