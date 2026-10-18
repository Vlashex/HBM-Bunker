package dev.deadearth.director;

import com.google.gson.Gson;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Optional separate save: bad analytics cannot replace bytes or disable the campaign. */
public final class EventAnalyticsStore extends SavedData {
    public static final String NAME="dead_earth_event_analytics";
    static final int MAX_LEDGER_BYTES=8*1024*1024;
    private static final Gson JSON=new Gson();
    private UUID campaign;
    private EventAnalytics analytics;
    private CompoundTag unsupported;
    public static EventAnalyticsStore create(UUID campaign,EventAnalytics.Settings settings){var s=new EventAnalyticsStore();s.campaign=campaign;s.analytics=new EventAnalytics(settings);s.setDirty();return s;}
    public static EventAnalyticsStore open(ServerLevel level,UUID campaign,EventAnalytics.Settings settings) {
        try{return dev.deadearth.campaign.SavedDataAccess.open(level,NAME,new SavedData.Factory<>(()->create(campaign,settings),(tag,reg)->load(campaign,settings,tag,reg)));}
        catch(RuntimeException invalid){dev.deadearth.DeadEarth.LOGGER.error("Event analytics save unreadable; original retained",invalid);var s=new EventAnalyticsStore();s.unsupported=new CompoundTag();return s;}
    }
    public static EventAnalyticsStore load(UUID expected,EventAnalytics.Settings settings,CompoundTag tag,HolderLookup.Provider registries) {
        var s=new EventAnalyticsStore();
        try {
            if(!tag.contains("schema",Tag.TAG_INT)||!java.util.Set.of(1,2).contains(tag.getInt("schema"))
                ||!tag.contains("campaign",Tag.TAG_STRING)||!expected.toString().equals(tag.getString("campaign")))throw new IllegalArgumentException("Analytics schema");
            byte[] bytes;
            if(tag.getInt("schema")==1) {
                if(!tag.contains("ledger",Tag.TAG_STRING))throw new IllegalArgumentException("Analytics ledger type");
                bytes=tag.getString("ledger").getBytes(StandardCharsets.UTF_8);
            } else {
                if(!tag.contains("ledger",Tag.TAG_BYTE_ARRAY))throw new IllegalArgumentException("Analytics ledger type");
                bytes=tag.getByteArray("ledger");
            }
            if(bytes.length>MAX_LEDGER_BYTES)throw new IllegalArgumentException("Analytics ledger size");
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var raw=CinematicCatalog.strict(text,MAX_LEDGER_BYTES);var data=JSON.fromJson(raw,EventAnalytics.Stored.class);
            if(!JSON.toJsonTree(data).equals(raw))throw new IllegalArgumentException("Analytics stored fields");
            s.campaign=expected;s.analytics=EventAnalytics.restore(settings,data);s.setDirty();
        }catch(RuntimeException|CharacterCodingException invalid){s.unsupported=tag.copy();s.setDirty(false);}
        return s;
    }
    public boolean enabled(){return unsupported==null;}
    public EventAnalytics analytics(){if(!enabled())throw new IllegalStateException("Unsupported analytics save");return analytics;}
    public boolean reconfigure(EventAnalytics.Settings settings){if(!enabled()||analytics.settings().equals(settings))return false;analytics=EventAnalytics.restore(settings,analytics.snapshot());setDirty();return true;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();
        var bytes=JSON.toJson(analytics.snapshot()).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_LEDGER_BYTES)throw new IllegalStateException("Analytics ledger exceeds storage envelope");
        // NBT strings use writeUTF (65535 bytes); byte arrays preserve a full bounded journal.
        tag.putInt("schema",2);tag.putString("campaign",campaign.toString());tag.putByteArray("ledger",bytes);return tag;
    }
}
