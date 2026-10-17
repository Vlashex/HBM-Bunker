package dev.deadearth.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.deadearth.content.TraceContent;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid="dead_earth",value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class TraceRenderer implements BlockEntityRenderer<TraceContent.TraceEntity> {
    private static final ResourceLocation TEXTURE=ResourceLocation.fromNamespaceAndPath("dead_earth","textures/block/trace_pixel.png");
    public TraceRenderer(BlockEntityRendererProvider.Context context){}
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(TraceContent.ENTITY.get(),TraceRenderer::new);}
    @Override public int getViewDistance(){return 48;}
    @Override public void render(TraceContent.TraceEntity entity,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay) {
        if(entity.getLevel()==null)return;
        var mask=entity.mask();var consumer=buffers.getBuffer(RenderType.entityTranslucent(TEXTURE));
        float fade=Math.clamp((entity.getLevel().getGameTime()-entity.appeared()+partial)/40f,0,1);
        int color=entity.color(),red=(color>>16)&255,green=(color>>8)&255,blue=color&255;
        float height=1f/64,width=1f/mask.getFirst().length(),depth=1f/mask.size();var pose=poses.last();
        for(int z=0;z<mask.size();z++)for(int x=0;x<mask.get(z).length();x++) {
            char value=mask.get(z).charAt(x);if(value=='.')continue;
            int alpha=(int)((value=='1'?120:value=='2'?180:220)*fade);
            float x0=x*width,x1=(x+1)*width,z0=z*depth,z1=(z+1)*depth;
            consumer.addVertex(pose,x0,height,z0).setColor(red,green,blue,alpha).setUv(0,0).setOverlay(overlay).setLight(light).setNormal(pose,0,1,0);
            consumer.addVertex(pose,x0,height,z1).setColor(red,green,blue,alpha).setUv(0,1).setOverlay(overlay).setLight(light).setNormal(pose,0,1,0);
            consumer.addVertex(pose,x1,height,z1).setColor(red,green,blue,alpha).setUv(1,1).setOverlay(overlay).setLight(light).setNormal(pose,0,1,0);
            consumer.addVertex(pose,x1,height,z0).setColor(red,green,blue,alpha).setUv(1,0).setOverlay(overlay).setLight(light).setNormal(pose,0,1,0);
        }
    }
}
