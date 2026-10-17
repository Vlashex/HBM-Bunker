package dev.deadearth.content;

import com.mojang.serialization.MapCodec;
import dev.deadearth.DeadEarth;
import dev.deadearth.director.CinematicCatalog;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/** One small block entity per clue; its mask is synchronized, so new patterns need no client rebuild. */
public final class TraceContent {
    public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(DeadEarth.MOD_ID);
    public static final DeferredBlock<TraceBlock> TRACE=BLOCKS.register("ground_trace",()->new TraceBlock(
            BlockBehaviour.Properties.of().strength(.1f).noCollission().noOcclusion().noLootTable().sound(SoundType.MUD)));
    public static final DeferredRegister<BlockEntityType<?>> TYPES=DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE,DeadEarth.MOD_ID);
    public static final java.util.function.Supplier<BlockEntityType<TraceEntity>> ENTITY=TYPES.register("ground_trace",()->BlockEntityType.Builder.of(TraceEntity::new,TRACE.get()).build(null));
    public static void register(IEventBus bus){BLOCKS.register(bus);TYPES.register(bus);}
    private TraceContent() {}
    public static final class TraceBlock extends BaseEntityBlock {
        static final MapCodec<TraceBlock> CODEC=simpleCodec(TraceBlock::new);
        public TraceBlock(BlockBehaviour.Properties p){super(p);}
        @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
        @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.INVISIBLE;}
        @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos p,CollisionContext c){return Block.box(0,0,0,16,.5,16);}
        @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new TraceEntity(p,s);}
        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> t){
            return l.isClientSide?null:createTickerHelper(t,ENTITY.get(),TraceEntity::tick);
        }
        @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
            if(!level.isClientSide&&player instanceof ServerPlayer p&&level.getBlockEntity(pos) instanceof TraceEntity trace)
                trace.signal(p,"inspect");
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
            if(!stack.is(Items.FLINT_AND_STEEL)&&!stack.is(Items.WATER_BUCKET))return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if(!level.isClientSide&&player instanceof ServerPlayer p&&level.getBlockEntity(pos) instanceof TraceEntity trace) {
                boolean water=stack.is(Items.WATER_BUCKET);trace.signal(p,water?"water":"burn");
                if(!player.getAbilities().instabuild) {
                    if(water)player.setItemInHand(hand,new ItemStack(Items.BUCKET));
                    else stack.hurtAndBreak(1,p,net.minecraft.world.entity.LivingEntity.getSlotForHand(hand));
                }
                var particles=water?net.minecraft.core.particles.ParticleTypes.SPLASH:net.minecraft.core.particles.ParticleTypes.SMOKE;
                ((ServerLevel)level).sendParticles(particles,pos.getX()+.5,pos.getY()+.15,pos.getZ()+.5,16,.3,.1,.3,.015);
                level.removeBlock(pos,false);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        @Override public BlockState playerWillDestroy(Level l,BlockPos pos,BlockState state,Player player) {
            if(player instanceof ServerPlayer p&&l.getBlockEntity(pos) instanceof TraceEntity trace)trace.signal(p,"break");
            return super.playerWillDestroy(l,pos,state,player);
        }
    }
    public static final class TraceEntity extends BlockEntity {
        private UUID operation;
        private String role="approach";
        private List<String> mask=List.of("....",".11.",".11.","....");
        private int color=0x666655;
        private long expires;
        private long appeared;
        public TraceEntity(BlockPos p,BlockState s){super(ENTITY.get(),p,s);}
        public List<String> mask(){return mask;}
        public int color(){return color;}
        public UUID operation(){return operation;}
        public String role(){return role;}
        public long appeared(){return appeared;}
        public void configure(UUID op,String r,CinematicCatalog.Trace pattern,long expiry) {
            operation=op;role=r;mask=pattern.mask();color=pattern.color();expires=expiry;
            appeared=level.getGameTime();setChanged();level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
        }
        public void signal(ServerPlayer player,String kind) {
            if(operation==null||player.isSpectator()||!player.isAlive())return;
            try{DeadEarth.runtime(player.getServer()).cinematicSignal(operation,kind,role);}catch(IllegalStateException unavailable){}
        }
        static void tick(Level level,BlockPos pos,BlockState state,TraceEntity trace) {
            if(level.getGameTime()%20!=0)return;
            try {
                long now=DeadEarth.runtime(((ServerLevel)level).getServer()).campaign().campaign().activeTicks();
                if(trace.operation==null||now>=trace.expires)level.removeBlock(pos,false);
                else DeadEarth.runtime(((ServerLevel)level).getServer()).cinematics().observeTrace(trace);
            } catch(IllegalStateException unavailable){}
        }
        @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider) {
            super.saveAdditional(tag,provider);if(operation!=null)tag.putUUID("operation",operation);
            tag.putString("role",role);tag.putInt("color",color);tag.putLong("expires",expires);tag.putLong("appeared",appeared);
            var rows=new ListTag();mask.forEach(s->rows.add(StringTag.valueOf(s)));tag.put("mask",rows);
        }
        @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider provider) {
            super.loadAdditional(tag,provider);operation=tag.hasUUID("operation")?tag.getUUID("operation"):null;
            role=tag.getString("role");if(!CinematicCatalog.ROLES.contains(role))operation=null;
            color=tag.getInt("color")&0xffffff;expires=tag.getLong("expires");appeared=tag.getLong("appeared");
            var rows=tag.getList("mask",Tag.TAG_STRING);var out=new ArrayList<String>();
            if(rows.size()<4||rows.size()>16){operation=null;return;}
            for(int i=0;i<rows.size();i++){String s=rows.getString(i);if(!s.matches("[.123]{4,16}")){operation=null;return;}out.add(s);}
            int width=out.getFirst().length();if(out.stream().anyMatch(s->s.length()!=width)){operation=null;return;}
            mask=List.copyOf(out);
        }
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider p){return saveWithoutMetadata(p);}
        @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    }
}
