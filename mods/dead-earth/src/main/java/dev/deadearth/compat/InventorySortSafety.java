package dev.deadearth.compat;

import dev.deadearth.config.DeadEarthConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;

/** Admission policy for Inventory Sorter's native server actions, not a replacement sorter. */
public final class InventorySortSafety {
    private InventorySortSafety() {}

    public static boolean allows(ServerPlayer player) {
        var menu = player.containerMenu;
        if (menu == null) return false;
        if (menu == player.inventoryMenu) return true;
        try {
            var type = menu.getType();
            if (type == null) return false;
            var id = BuiltInRegistries.MENU.getKey(type);
            return id != null && (id.getNamespace().equals("minecraft")
                    || DeadEarthConfig.SORT_ALLOWED_MENUS.get().contains(id.toString()));
        } catch (RuntimeException unavailableMenuMetadata) {
            // An unknown/unregistered menu or unavailable config must never admit an action.
            return false;
        }
    }
}
