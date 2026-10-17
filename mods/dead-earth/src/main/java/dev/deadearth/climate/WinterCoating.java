package dev.deadearth.climate;

import static dev.deadearth.config.DeadEarthConfig.*;
import dev.deadearth.content.SeasonBlocks;
import dev.deadearth.content.SeasonalCoatingBlock;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Loaded-only exterior shell; original geometry, never our own shell, is its anchor. */
public final class WinterCoating {
    private static final int MAX_REACH=38;
    // Config bounds: side ice (4) + side snow (4) + side relief (2).
    private static final int MAX_SIDE_REACH=10;
    private static final List<List<Offset>> OFFSETS=buildOffsets();
    private static final Map<ServerLevel,Long> WINTER_SEEDS=new WeakHashMap<>();
    private static final Map<ServerLevel,LinkedHashSet<BlockPos>> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerLevel,LinkedHashSet<BlockPos>> THAW=new WeakHashMap<>();
    private WinterCoating() {}
    public static void selectWinter(ServerLevel level,long winterNumber) {WINTER_SEEDS.put(level,WinterTerrain.seed(level.getSeed(),winterNumber));}
    public static void clearWinter(ServerLevel level) {WINTER_SEEDS.remove(level);}
    public static int maximumSideReach() {return i(SIDE_ICE)+i(SIDE_SNOW)+(b(WINTER_RELIEF)&&i(SIDE_ICE)>0?i(SIDE_RELIEF):0);}
    public static WinterTerrain.Thickness profile(ServerLevel level,int x,int z) {
        var relief=new WinterTerrain.Relief(0,0,0,0);var seed=WINTER_SEEDS.get(level);
        if(seed!=null&&b(WINTER_RELIEF))relief=WinterTerrain.sample(seed,x,z,new WinterTerrain.Settings(i(RELIEF_SCALE),i(ICE_RELIEF),i(SNOW_RELIEF),i(SIDE_RELIEF),
                i(PEAK_SPACING),d(PEAK_CHANCE),Math.min(i(PEAK_MIN_HEIGHT),i(PEAK_MAX_HEIGHT)),Math.max(i(PEAK_MIN_HEIGHT),i(PEAK_MAX_HEIGHT)),
                Math.min(i(PEAK_MIN_RADIUS),i(PEAK_MAX_RADIUS)),Math.max(i(PEAK_MIN_RADIUS),i(PEAK_MAX_RADIUS))));
        return WinterTerrain.thickness(relief,i(TOP_ICE),i(TOP_SNOW),i(SIDE_ICE),i(SIDE_SNOW),b(PEAK_ICY_TIPS));
    }
    public static final class Budget {
        private int remaining,changes,remainingCells=i(COATING_CELLS);
        private final int sectionLimit;
        private final Set<Long> sections=new HashSet<>();
        private final Map<Long,Integer> surfaces=new HashMap<>(),floors=new HashMap<>();
        public Budget(int edits,int sectionLimit) {remaining=edits;this.sectionLimit=sectionLimit;}
        public boolean exhausted() {return remaining<=0;}
        public int changes() {return changes;}
        public boolean reserveCells(int count) {
            if(count<0)throw new IllegalArgumentException("Negative cell work");
            if(count>remainingCells)return false;
            remainingCells-=count;return true;
        }
        public boolean write(ServerLevel level,BlockPos pos,BlockState state) {
            if(exhausted())return false;
            long section=net.minecraft.core.SectionPos.asLong(pos);
            if(!sections.contains(section)&&sections.size()>=sectionLimit)return false;
            if(!level.setBlock(pos,state,2|16))return false;
            sections.add(section);remaining--;changes++;return true;
        }
    }
    public static void requestThaw(ServerLevel level,BlockPos pos) {
        var queue=THAW.computeIfAbsent(level,k->new LinkedHashSet<>());
        if(queue.size()<i(COATING_QUEUE))queue.add(pos.immutable());
    }
    public static boolean requestColumn(ServerLevel level,int x,int z) {
        var queue=ACTIVE.computeIfAbsent(level,k->new LinkedHashSet<>());
        return queue.size()<i(COATING_QUEUE)&&queue.add(new BlockPos(x,0,z));
    }
    /** Development camera helper only; ordinary weather always uses FIFO requestColumn. */
    public static void prioritizeTestColumn(ServerLevel level,int x,int z) {
        var queue=ACTIVE.computeIfAbsent(level,k->new LinkedHashSet<>());var pos=new BlockPos(x,0,z);
        queue.remove(pos);
        if(queue.size()>=i(COATING_QUEUE))queue.removeLast();
        queue.addFirst(pos);
    }
    public static void drainActive(ServerLevel level,boolean winter,Budget budget) {
        int priorityLimit=Math.max(1,i(WEATHER_EDITS)/2),initialChanges=budget.changes();
        if(!winter) {
            var thaw=THAW.get(level);
            for(int n=0;thaw!=null&&!thaw.isEmpty()&&n<i(COATING_REVISITS)&&!budget.exhausted()&&budget.changes()-initialChanges<priorityLimit;n++) {
                var it=thaw.iterator();var pos=it.next();it.remove();
                if(!loaded(level,pos.getX(),pos.getZ(),1)||!ticking(level,pos.getX(),pos.getZ()))continue;
                var state=level.getBlockState(pos);
                var next=SeasonalCoatingBlock.isCoating(state)?SeasonalCoatingBlock.thawStep(level,pos,state):
                        state.is(SeasonBlocks.ICE.get())?(level.dimensionType().ultraWarm()?Blocks.AIR:Blocks.WATER).defaultBlockState():
                        state.is(SeasonBlocks.CRUST.get())?Blocks.AIR.defaultBlockState():state;
                if(next!=state)budget.write(level,pos,next);
                if(SeasonalCoatingBlock.isCoating(next))requestThaw(level,pos);
            }
        }
        var queue=ACTIVE.get(level);if(queue==null)return;
        int quota=Math.min(queue.size(),i(COATING_REVISITS));
        for(int n=0;n<quota&&!queue.isEmpty()&&!budget.exhausted()&&budget.changes()-initialChanges<priorityLimit;n++) {
            var it=queue.iterator();var pos=it.next();it.remove();
            if(!ticking(level,pos.getX(),pos.getZ()))continue;
            column(level,pos.getX(),pos.getZ(),winter,budget,Math.min(4,priorityLimit-(budget.changes()-initialChanges)));
        }
    }
    private static boolean ticking(ServerLevel level,int x,int z) {
        var holder=level.getChunkSource().chunkMap.getVisibleChunkIfPresent(net.minecraft.world.level.ChunkPos.asLong(x>>4,z>>4));
        return holder!=null&&holder.getTickingChunk()!=null;
    }
    public static boolean loaded(ServerLevel level,int x,int z,int reach) {
        return level.hasChunksAt(new BlockPos(x-reach,level.getMinBuildHeight(),z-reach),
                new BlockPos(x+reach,level.getMinBuildHeight(),z+reach));
    }
    public static void column(ServerLevel level,int x,int z,boolean winter,Budget budget) {
        column(level,x,z,winter,budget,Integer.MAX_VALUE);
    }
    private static void column(ServerLevel level,int x,int z,boolean winter,Budget budget,int quota) {
        var profile=profile(level,x,z);int side=maximumSideReach(),top=profile.top();
        if(!loaded(level,x,z,side+1))return;
        int initial=budget.changes();boolean pending=false;
        var offsets=offsets(side);
        int ceiling=surface(level,x,z,false,budget);
        for(var offset:offsets)ceiling=Math.max(ceiling,surface(level,x+offset.dx(),z+offset.dz(),false,budget));
        int start=Math.min(level.getMaxBuildHeight()-1,ceiling+Math.max(top,MAX_REACH));
        int floor=surface(level,x,z,true,budget);
        int bottom=Math.max(floor,Math.max(level.getMinBuildHeight(),ceiling-i(COATING_DEPTH)));
        if(!budget.reserveCells(Math.max(0,start-bottom+1))) {requestColumn(level,x,z);return;}
        for(int y=start;y>=bottom&&!budget.exhausted()&&budget.changes()-initial<quota;y--) {
            var pos=new BlockPos(x,y,z);var old=level.getBlockState(pos);
            if(!winter) {
                if(SeasonalCoatingBlock.isCoating(old)) {
                    pending=true;
                    var next=SeasonalCoatingBlock.thawStep(level,pos,old);
                    if(next!=old)budget.write(level,pos,next);
                } else if(old.is(SeasonBlocks.CRUST.get()))budget.write(level,pos,Blocks.AIR.defaultBlockState());
                continue;
            }
            if(y<floor || !replaceable(old))continue;
            Plan plan=plan(level,pos,profile,offsets);
            if(plan==null) {
                if(SeasonalCoatingBlock.isCoating(old)) {
                    var next=SeasonalCoatingBlock.thawStep(level,pos,old);pending=true;
                    if(next!=old)budget.write(level,pos,next);
                }
                continue;
            }
            var previous=level.getBlockState(pos.relative(plan.face().getOpposite()));
            if(SeasonalCoatingBlock.isCoating(previous)) {
                if(previous.getValue(SeasonalCoatingBlock.LAYERS)!=8) {pending=true;continue;}
            } else if(!anchor(level,pos.relative(plan.face().getOpposite()),previous))continue;
            int layers=SeasonalCoatingBlock.isCoating(old)?old.getValue(SeasonalCoatingBlock.LAYERS):0;
            if(layers>0&&layers<8)pending=true;
            var next=plan.state().setValue(SeasonalCoatingBlock.FACING,plan.face())
                    .setValue(SeasonalCoatingBlock.LAYERS,Math.min(8,layers==0?1:layers+i(COATING_STEP)));
            if(next!=old&&level.isUnobstructed(next,pos,CollisionContext.empty())&&!budget.write(level,pos,next))pending=true;
        }
        if(pending||budget.changes()>initial)requestColumn(level,x,z);
    }
    private record Plan(BlockState state,Direction face) {}
    private record Offset(int dx,int dz,int distance,Direction face) {
        private Offset(int dx,int dz,int distance) {
            this(dx,dz,distance,Math.abs(dx)>=Math.abs(dz)?(dx<0?Direction.EAST:Direction.WEST):(dz<0?Direction.SOUTH:Direction.NORTH));
        }
    }
    private static Plan plan(ServerLevel level,BlockPos pos,WinterTerrain.Thickness profile,List<Offset> offsets) {
        for(int d=1;d<=profile.top();d++) {
            var p=pos.below(d);var s=level.getBlockState(p);
            if(anchor(level,p,s))return new Plan((d<=profile.topIce()?SeasonBlocks.RIME.get():SeasonBlocks.SNOW.get()).defaultBlockState(),Direction.UP);
            if(!replaceable(s))break;
        }
        for(var offset:offsets) {
            int distance=offset.distance();if(distance>profile.sideIce()+profile.sideSnow())continue;
            var p=pos.offset(offset.dx(),0,offset.dz());var s=level.getBlockState(p);
            if(anchor(level,p,s)) {
                return new Plan((distance<=profile.sideIce()?SeasonBlocks.RIME.get():SeasonBlocks.SNOW.get()).defaultBlockState(),offset.face());
            }
        }
        return null;
    }
    private static List<Offset> offsets(int radius) {return OFFSETS.get(radius);}
    private static List<List<Offset>> buildOffsets() {
        var cached=new ArrayList<List<Offset>>(MAX_SIDE_REACH+1);
        for(int radius=0;radius<=MAX_SIDE_REACH;radius++) {
            var result=new ArrayList<Offset>(2*radius*(radius+1));
            for(int d=1;d<=radius;d++)for(int dx=-d;dx<=d;dx++) {
                int dz=d-Math.abs(dx);result.add(new Offset(dx,dz,d));if(dz!=0)result.add(new Offset(dx,-dz,d));
            }
            cached.add(List.copyOf(result));
        }
        return List.copyOf(cached);
    }
    private static boolean replaceable(BlockState s) {
        return s.isAir()||s.is(Blocks.SNOW)||s.is(Blocks.SHORT_GRASS)||s.is(Blocks.TALL_GRASS)
                ||s.is(Blocks.FERN)||s.is(Blocks.LARGE_FERN)||s.is(Blocks.DEAD_BUSH)
                ||s.is(SeasonBlocks.CRUST.get())||SeasonalCoatingBlock.isCoating(s);
    }
    private static boolean anchor(ServerLevel level,BlockPos pos,BlockState s) {
        return !replaceable(s)&&!s.hasBlockEntity()&&s.getFluidState().isEmpty()
                &&(s.is(BlockTags.LEAVES)||s.isCollisionShapeFullBlock(level,pos));
    }
    private static int surface(ServerLevel level,int x,int z,boolean ignoreLeaves,Budget budget) {
        var cache=ignoreLeaves?budget.floors:budget.surfaces;long key=BlockPos.asLong(x,0,z);
        if(cache.containsKey(key))return cache.get(key);
        int y=level.getHeight(ignoreLeaves?Heightmap.Types.MOTION_BLOCKING_NO_LEAVES:Heightmap.Types.MOTION_BLOCKING,x,z)-1;
        // A side shell can sit high above ground; skip empty space and foliage too,
        // otherwise yesterday's coating becomes an artificial roof/anchor.
        while(y>=level.getMinBuildHeight()) {
            var s=level.getBlockState(new BlockPos(x,y,z));
            if(!replaceable(s)&&!(ignoreLeaves&&(s.getBlock() instanceof LeavesBlock||s.is(BlockTags.LEAVES)))) {cache.put(key,y+1);return y+1;}
            // Entire empty sections can be skipped without probing all sixteen blocks.
            var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
            if(s.isAir()&&chunk.getSection(level.getSectionIndex(y)).hasOnlyAir()) {y=(y>>4<<4)-1;continue;}
            y--;
        }
        cache.put(key,level.getMinBuildHeight());return level.getMinBuildHeight();
    }
}
