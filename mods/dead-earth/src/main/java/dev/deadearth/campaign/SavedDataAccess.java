package dev.deadearth.campaign;

import java.nio.file.Files;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

public final class SavedDataAccess {
    private SavedDataAccess() {}

    /** A failed read must never silently replace an existing campaign. Overworld only. */
    public static <T extends SavedData> T open(ServerLevel overworld, String name, SavedData.Factory<T> factory) {
        var storage = overworld.getDataStorage();
        T existing = storage.get(factory, name);
        if (existing != null) return existing;
        var file = overworld.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
        if (Files.exists(file)) throw new IllegalStateException("Cannot read existing Dead Earth save: " + name);
        T created = factory.constructor().get();
        storage.set(name, created);
        return created;
    }
}
