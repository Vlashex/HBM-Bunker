import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Streaming JFR reduction: raw recordings remain outside Git. Output is TSV. */
public final class JfrSummary {
    record Phase(String name,long start,long end) {}
    private static final Map<String,Long> counts=new TreeMap<>();
    private static final Map<String,Double> durations=new TreeMap<>();
    private static final List<Phase> phases=new ArrayList<>();
    private static String phase(long time){for(var row:phases)if(time>=row.start&&time<=row.end)return row.name;return "outside_phases";}
    private static void add(String key){counts.merge(key,1L,Long::sum);}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("recording.jfr phases.tsv");
        for(String line:Files.readAllLines(Path.of(args[1]))) {
            String[] fields=line.split("\t");if(fields.length==3)phases.add(new Phase(fields[0],Long.parseLong(fields[1]),Long.parseLong(fields[2])));
        }
        try(var recording=new RecordingFile(Path.of(args[0]))) {
            while(recording.hasMoreEvents()) {
                var event=recording.readEvent();String type=event.getEventType().getName();add("EVENT\tall\t"+type);
                String phase=phase(event.getStartTime().toEpochMilli());
                if(type.equals("jdk.ExecutionSample")||type.equals("jdk.NativeMethodSample")) {
                    String kind=type.equals("jdk.ExecutionSample")?"EXEC":"NATIVE";
                    var thread=event.hasField("sampledThread")?event.getThread("sampledThread"):event.getThread();
                    String name=thread==null?"unknown":thread.getJavaName();var stack=event.getStackTrace();
                    add("SAMPLES\t"+phase+"\t"+name);
                    add("SAMPLES_"+kind+"\t"+phase+"\t"+name);
                    if(stack==null||stack.getFrames().isEmpty()){add("NO_STACK\t"+phase+"\t"+name);continue;}
                    var seen=new HashSet<String>();String leaf=null,game=null;
                    for(var frame:stack.getFrames()) {
                        var method=frame.getMethod();String owner=method.getType().getName();String function=owner+"."+method.getName();
                        if(leaf==null)leaf=function;
                        if(game==null&&(owner.startsWith("net.minecraft.")||owner.startsWith("dev.deadearth.")||owner.startsWith("com.hbm.")||owner.startsWith("com.asestefan.")||owner.startsWith("com.yayuy.")||owner.startsWith("io.github.flemmli97.")||owner.startsWith("appeng.")||owner.startsWith("net.irisshaders.")||owner.startsWith("net.caffeinemc.")||owner.startsWith("software.bernie.geckolib.")||owner.startsWith("com.mojang.blaze3d.")))game=function;
                        if(seen.add(function)){add("INCLUSIVE\t"+phase+"\t"+name+"\t"+function);add("INCLUSIVE_"+kind+"\t"+phase+"\t"+name+"\t"+function);}
                    }
                    add("LEAF\t"+phase+"\t"+name+"\t"+leaf);
                    add("LEAF_"+kind+"\t"+phase+"\t"+name+"\t"+leaf);
                    if(game!=null){add("GAME\t"+phase+"\t"+name+"\t"+game);add("GAME_"+kind+"\t"+phase+"\t"+name+"\t"+game);}
                } else if(type.equals("jdk.GarbageCollection")||type.equals("minecraft.ChunkGeneration")) {
                    String key="DURATION\t"+phase+"\t"+type;
                    add(key);durations.merge(key,event.getDuration().toNanos()/1_000_000d,Double::sum);
                }
            }
        }
        for(var row:counts.entrySet())System.out.println(row.getKey()+"\t"+row.getValue()+"\t"+durations.getOrDefault(row.getKey(),0d));
    }
}
