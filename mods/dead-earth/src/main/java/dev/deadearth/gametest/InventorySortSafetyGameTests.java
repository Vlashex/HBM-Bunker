package dev.deadearth.gametest;

import dev.deadearth.config.DeadEarthConfig;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Executes the pinned mod's actual main-thread packet worker, including the injected guard. */
@GameTestHolder("dead_earth_inventory")
@PrefixGameTestTemplate(false)
public final class InventorySortSafetyGameTests {
    private static final String[] ACTIONS = {"SORT", "ONEITEMIN", "ONEITEMOUT"};

    @GameTest(template="empty", batch="inventory_safety")
    public static void nativeActionsCannotChangeModdedMenu(GameTestHelper helper) throws Exception {
        var previous = DeadEarthConfig.SORT_ALLOWED_MENUS.get();
        try (var client = FoundationGameTests.attach(helper)) {
            DeadEarthConfig.SORT_ALLOWED_MENUS.set(List.of());
            for (String action : ACTIONS) {
                var menu = new FixtureMenu(moddedType(), false);
                var before = snapshot(menu);
                client.player().containerMenu = menu;
                execute(action, menu.getSlot(0), client.player());
                assertUnchanged(helper, before, menu, action + " changed a denied menu");
                // The lower player inventory must not bypass a denied machine's boundary.
                menu = new FixtureMenu(moddedType(), false);
                int playerSlot = menu.addPlayerInventory(client.player());
                client.player().getInventory().clearContent();
                client.player().getInventory().setItem(9, new ItemStack(Items.STONE, 8));
                client.player().getInventory().setItem(10, new ItemStack(Items.STONE, 16));
                client.player().containerMenu = menu;
                before = snapshot(menu);
                execute(action, menu.getSlot(playerSlot), client.player());
                assertUnchanged(helper, before, menu, action + " bypassed denial through the player inventory");
            }
        } finally { DeadEarthConfig.SORT_ALLOWED_MENUS.set(previous); }
        helper.succeed();
    }

    @GameTest(template="empty", batch="inventory_safety")
    public static void nativeVanillaActionsStillMoveRealItems(GameTestHelper helper) throws Exception {
        try (var client = FoundationGameTests.attach(helper)) {
            for (String action : ACTIONS) {
                var menu = new FixtureMenu(MenuType.GENERIC_9x3, false);
                client.player().containerMenu = menu;
                execute(action, menu.getSlot(0), client.player());
                int expected = action.equals("SORT") ? 24 : action.equals("ONEITEMIN") ? 9 : 7;
                helper.assertTrue(menu.getSlot(0).getItem().getCount() == expected,
                        action + " did not execute through the native worker");
                helper.assertTrue(menu.slots.stream().mapToInt(s -> s.getItem().getCount()).sum() == 28,
                        action + " did not conserve ordinary items");
            }
            var player = client.player();
            player.getInventory().clearContent();
            player.getInventory().setItem(9, new ItemStack(Items.STONE, 8));
            player.getInventory().setItem(10, new ItemStack(Items.STONE, 16));
            player.containerMenu = player.inventoryMenu;
            execute("SORT", player.inventoryMenu.getSlot(9), player);
            helper.assertTrue(player.getInventory().getItem(9).getCount() == 24
                    && player.getInventory().getItem(10).isEmpty(), "Own inventory sorting was blocked");
        }
        helper.succeed();
    }

    @GameTest(template="empty", batch="inventory_safety")
    public static void nativeAllowlistRequiresExactMenuId(GameTestHelper helper) throws Exception {
        var previous = DeadEarthConfig.SORT_ALLOWED_MENUS.get();
        var type = moddedType();
        var id = BuiltInRegistries.MENU.getKey(type);
        try (var client = FoundationGameTests.attach(helper)) {
            for (String entry : List.of(id.getNamespace() + ":*", "*", id + "_other")) {
                DeadEarthConfig.SORT_ALLOWED_MENUS.set(List.of(entry));
                var menu = new FixtureMenu(type, false);
                var before = snapshot(menu);
                client.player().containerMenu = menu;
                execute("SORT", menu.getSlot(0), client.player());
                assertUnchanged(helper, before, menu, "Non-exact allowlist entry admitted a menu: " + entry);
            }
            DeadEarthConfig.SORT_ALLOWED_MENUS.set(List.of(id.toString()));
            var menu = new FixtureMenu(type, false);
            client.player().containerMenu = menu;
            execute("SORT", menu.getSlot(0), client.player());
            helper.assertTrue(menu.getSlot(0).getItem().getCount() == 24,
                    "Exact menu allowlist entry did not admit native sorting");
        } finally { DeadEarthConfig.SORT_ALLOWED_MENUS.set(previous); }
        helper.succeed();
    }

    @GameTest(template="empty", batch="inventory_safety")
    public static void nativeWorkerRejectsUnknownAndCurrentDeniedMenu(GameTestHelper helper) throws Exception {
        var previous = DeadEarthConfig.SORT_ALLOWED_MENUS.get();
        try (var client = FoundationGameTests.attach(helper)) {
            DeadEarthConfig.SORT_ALLOWED_MENUS.set(List.of());
            var menu = new FixtureMenu(null, false);
            client.player().containerMenu = menu;
            var before = snapshot(menu);
            execute("SORT", menu.getSlot(0), client.player());
            assertUnchanged(helper, before, menu, "Unknown menu was allowed");
            var oldMenu = new FixtureMenu(MenuType.GENERIC_9x3, false);
            var pending = message("SORT", oldMenu.getSlot(0));
            var current = new FixtureMenu(moddedType(), false);
            client.player().containerMenu = current;
            before = snapshot(current);
            invokeWorker(pending, client.player());
            assertUnchanged(helper, before, current, "Worker checked an old menu instead of the current menu");
        } finally { DeadEarthConfig.SORT_ALLOWED_MENUS.set(previous); }
        helper.succeed();
    }

    @GameTest(template="empty", batch="inventory_safety")
    public static void nativeWorkerDoesNotWriteExcludedEmptyHbmGhost(GameTestHelper helper) throws Exception {
        var previous = DeadEarthConfig.SORT_ALLOWED_MENUS.get();
        String ghost = "com.hbm.inventory.slot.SlotPattern";
        Set<String> blacklist = nativeSlotBlacklist();
        boolean added = blacklist.add(ghost);
        try (var client = FoundationGameTests.attach(helper)) {
            DeadEarthConfig.SORT_ALLOWED_MENUS.set(List.of());
            var menu = new FixtureMenu(moddedType(), true);
            var before = snapshot(menu);
            client.player().containerMenu = menu;
            execute("SORT", menu.getSlot(0), client.player());
            helper.assertTrue(menu.getSlot(1).getItem().isEmpty(),
                    "Native sorter wrote an excluded empty HBM ghost inside its min/max range");
            assertUnchanged(helper, before, menu, "Denied ghost fixture changed real items");
        } finally {
            if (added) blacklist.remove(ghost);
            DeadEarthConfig.SORT_ALLOWED_MENUS.set(previous);
        }
        helper.succeed();
    }

    private static MenuType<?> moddedType() {
        var id = BuiltInRegistries.MENU.keySet().stream()
                .filter(key -> key.getNamespace().equals("hbm"))
                .sorted(java.util.Comparator.comparing(ResourceLocation::toString)).findFirst().orElseThrow();
        return BuiltInRegistries.MENU.get(id);
    }

    private static void execute(String action, Slot slot, ServerPlayer player) throws Exception {
        invokeWorker(message(action, slot), player);
    }

    private static Object message(String name, Slot slot) throws Exception {
        var actionClass = Class.forName("cpw.mods.inventorysorter.Action");
        Object action = actionClass.getField(name).get(null);
        return actionClass.getMethod("message", Slot.class).invoke(action, slot);
    }

    private static void invokeWorker(Object message, ServerPlayer player) throws Exception {
        if (!player.getServer().isSameThread()) throw new IllegalStateException("Native worker requires server thread");
        var worker = Class.forName("cpw.mods.inventorysorter.ServerHandler").getDeclaredMethod(
                "lambda$onMessage$0", message.getClass(), ServerPlayer.class);
        worker.setAccessible(true);
        try { worker.invoke(null, message, player); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> nativeSlotBlacklist() throws Exception {
        var sorter = Class.forName("cpw.mods.inventorysorter.InventorySorter");
        var method = sorter.getDeclaredMethod("slotblacklist");
        method.setAccessible(true);
        return (Set<String>) method.invoke(sorter.getField("INSTANCE").get(null));
    }

    private static List<ItemStack> snapshot(AbstractContainerMenu menu) {
        var result = new ArrayList<ItemStack>();
        for (Slot slot : menu.slots) result.add(slot.getItem().copy());
        return result;
    }

    private static void assertUnchanged(GameTestHelper helper, List<ItemStack> before,
                                         AbstractContainerMenu menu, String reason) {
        for (int i = 0; i < before.size(); i++)
            helper.assertTrue(ItemStack.matches(before.get(i), menu.getSlot(i).getItem()), reason + " at slot " + i);
    }

    private static final class FixtureMenu extends AbstractContainerMenu {
        FixtureMenu(MenuType<?> type, boolean ghost) throws Exception {
            super(type, 42);
            var first = new SimpleContainer(ghost ? 3 : 2);
            addSlot(new Slot(first, 0, 0, 0));
            if (ghost) {
                addSlot((Slot) Class.forName("com.hbm.inventory.slot.SlotPattern")
                        .getConstructor(Container.class, int.class, int.class, int.class).newInstance(first, 1, 0, 0));
                addSlot(new Slot(first, 2, 0, 0));
                first.setItem(0, new ItemStack(Items.STONE, 64));
                first.setItem(2, new ItemStack(Items.DIRT, 64));
            } else {
                addSlot(new Slot(first, 1, 0, 0));
                var second = new SimpleContainer(2);
                addSlot(new Slot(second, 0, 0, 0));
                addSlot(new Slot(second, 1, 0, 0));
                first.setItem(0, new ItemStack(Items.STONE, 8));
                first.setItem(1, new ItemStack(Items.STONE, 16));
                second.setItem(0, new ItemStack(Items.STONE, 4));
            }
        }
        int addPlayerInventory(ServerPlayer player) {
            // Keep this fixture above 40 GUI slots: 24.0.24 reads GUI slot 40 when size >= 40.
            addSlot(new Slot(new SimpleContainer(1), 0, 0, 0));
            int first = slots.size();
            for (int i = 9; i < 36; i++) addSlot(new Slot(player.getInventory(), i, 0, 0));
            for (int i = 0; i < 9; i++) addSlot(new Slot(player.getInventory(), i, 0, 0));
            return first;
        }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }
}
