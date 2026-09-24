package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import java.util.List;
import org.lwjgl.glfw.GLFW;

public final class ItemEditorTest implements FabricClientGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runOnServer(s -> {
                for (var template : TemplateScreen.ALL) {
                    try {
                        var root = new CompoundTag(); root.putString("id", "minecraft:stick"); root.putInt("count", 1);
                        var components = new CompoundTag();
                        components.put("minecraft:" + template.key(), TagParser.create(NbtOps.INSTANCE).parseFully(template.value()));
                        root.put("components", components); StackData.decode(root, s.registryAccess());
                    } catch (Exception ex) { throw new AssertionError("Invalid 1.21.11 template: " + template.key(), ex); }
                }
            });
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst(); p.setGameMode(GameType.SURVIVAL);
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.enchant(s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
                var data = new CompoundTag(); data.putLong("long", 9007199254740993L); data.putIntArray("array", new int[]{1,2,3});
                var child = new CompoundTag(); child.putString("label", "中文 nested"); data.put("child", child);
                sword.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                p.getInventory().setItem(0, sword);
            });
            context.waitFor(c -> c.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
            context.runOnClient(c -> c.setScreen(new InventoryScreen(c.player)));
            hover(context, 36);
            context.getInput().pressKey(GLFW.GLFW_KEY_X);
            context.waitForScreen(SimpleComponentScreen.class);
            AttributeEditorTest.exercise(context, server);
            FoodPotionEditorTest.exercise(context, server);
            EnchantmentEditorTest.exercise(context, server);
            DisplayContainerTest.exercise(context, server);
            click(context, "高级模式");
            context.waitForScreen(ItemEditorScreen.class);
            context.runOnClient(c -> {
                var session = ((ItemEditorScreen)c.screen).session();
                check(session.editable, "Singleplayer survival is editable");
                check(session.preview.is(Items.DIAMOND_SWORD), "Preview is original sword");
                CompoundTag root = session.document.copy(); root.putString("id", "minecraft:apple"); root.putInt("count", 3);
                root.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:enchantments").putInt("minecraft:sharpness", 1000);
                session.edit(root);
                check(session.error.isEmpty(), "Unusual enchantment must decode: " + session.error);
                check(!StackData.risky(root), "Ordinary custom data must not warn: " + StackData.riskReason(root) + " in " + root);
                check(session.preview.is(Items.APPLE), "Item id changes preview immediately");
                check(session.preview.get(DataComponents.ENCHANTMENTS).getLevel(c.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)) == 1000,
                        "Level 1000 is preserved without clamping");
                check(ItemStack.CODEC.encodeStart(c.level.registryAccess().createSerializationContext(NbtOps.INSTANCE),session.preview).error().isPresent(),
                        "Vanilla persistence still rejects out-of-range levels");
                // Full signed levels are exercised as memory values; live packet tests use the vanilla range.
                root.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:enchantments").putInt("minecraft:sharpness",255);
                session.edit(root);
            });
            server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).is(Items.DIAMOND_SWORD), "Editing must not write the original"));
            click(context, "同步");
            context.waitFor(c -> ((ItemEditorScreen)c.screen).session().status.equals("同步成功"));
            server.runOnServer(s -> {
                var item = s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0);
                check(item.is(Items.APPLE) && item.getCount() == 3, "Write must reach the exact inventory index");
                check(item.get(DataComponents.CUSTOM_DATA).copyTag().getLongOr("long", 0) == 9007199254740993L, "Long precision is lossless");
                ItemStack.CODEC.encodeStart(s.registryAccess().createSerializationContext(NbtOps.INSTANCE), item).getOrThrow();
            });
            context.waitTicks(3);
            context.runOnClient(c -> check(!((ItemEditorScreen)c.screen).session().changed, "Successful sync resets baseline"));
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().setItem(0, new ItemStack(Items.DIRT, 2)));
            context.waitFor(c -> ((ItemEditorScreen)c.screen).session().changed);
            context.runOnClient(c -> {
                var session = ((ItemEditorScreen)c.screen).session(); var data = session.document.copy(); data.putInt("count", 4); session.edit(data);
            });
            click(context, "同步"); context.waitFor(c -> ((ItemEditorScreen)c.screen).session().status.equals("同步成功"));
            server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).is(Items.APPLE), "External replacement does not block overwrite"));
            click(context, "原始数据"); click(context, "展开");
            context.takeScreenshot("nbt-maker-raw-tree");
            click(context, "编辑"); context.waitForScreen(ValueScreen.class);
            context.takeScreenshot("nbt-maker-value-editor");
            click(context, "取消");
            context.runOnClient(c -> { c.options.guiScale().set(4); c.resizeDisplay(); });
            context.waitTick(); context.takeScreenshot("nbt-maker-small-gui");
            context.runOnClient(c -> { c.options.guiScale().set(2); c.resizeDisplay(); });
            context.runOnClient(c -> {
                var session = ((ItemEditorScreen)c.screen).session();
                var doc = session.document.copy(); doc.putInt("count", 200); session.edit(doc);
            });
            click(context, "同步");
            context.runOnClient(c -> check(c.screen.getTitle().getString().equals("高风险数据"), "Oversized stacks warn before sync"));
            click(context, "取消");
            server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).getCount() == 4, "Risk cancel must not write"));
            click(context, "同步"); click(context, "确定同步");
            context.waitFor(c -> ((ItemEditorScreen)c.screen).session().status.equals("同步成功"));
            server.runOnServer(s -> {
                var item = s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0);
                check(item.getCount() == 200, "Forced unusual count is not clamped");
                var tag = ItemStack.CODEC.encodeStart(s.registryAccess().createSerializationContext(NbtOps.INSTANCE), item).getOrThrow();
                check(ItemStack.CODEC.parse(s.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow().getCount() == 200, "Count survives persistence codec");
            });
            context.runOnClient(c -> {
                var session = ((ItemEditorScreen)c.screen).session(); var copy = session.document.copy(); copy.putInt("count", 7); session.edit(copy);
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_E);
            context.waitForScreen(InventoryScreen.class);
            server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).getCount() == 200, "E discards unsynced changes"));
            context.runOnClient(c -> c.screen.onClose()); context.waitTicks(2);
            testContainer(context, server);
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("see")) {
            try {
                var integration = (FabricClientGameTest) Class.forName("com.sinkie114.client.SeeIntegrationTest").getConstructor().newInstance();
                integration.runTest(context);
            } catch (ReflectiveOperationException ex) { throw new AssertionError("Missing SEE integration suite", ex); }
        }
    }

    private void testContainer(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst(); var container = new SimpleContainer(27);
            var box = new ItemStack(Items.SHULKER_BOX);
            var doc = StackData.encode(box, s.registryAccess());
            try { doc.getCompoundOrEmpty("components").put("minecraft:container", TagParser.create(NbtOps.INSTANCE).parseFully("[{slot:0,item:{id:\"minecraft:diamond\",count:2}}]")); }
            catch (Exception ex) { throw new RuntimeException(ex); }
            container.setItem(0, StackData.decode(doc, s.registryAccess()));
            container.setItem(1, new ItemStack(Items.STONE, 5));
            p.openMenu(new SimpleMenuProvider((id, inv, player) -> ChestMenu.threeRows(id, inv, container), Component.literal("Test chest")));
        });
        context.waitFor(c -> c.screen instanceof AbstractContainerScreen<?> sc && sc.getMenu() instanceof ChestMenu);
        context.runOnClient(c -> c.gameMode.handleInventoryMouseClick(c.player.containerMenu.containerId, 1, 0,
                net.minecraft.world.inventory.ClickType.PICKUP, c.player));
        context.waitFor(c -> c.player.containerMenu.getCarried().getCount() == 5);
        hover(context, 0); context.getInput().pressKey(GLFW.GLFW_KEY_X); context.waitForScreen(SimpleComponentScreen.class);
        click(context, "高级模式"); context.waitForScreen(ItemEditorScreen.class);
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().containerMenu.getCarried().getCount() == 5,
                "Opening inspector with a carried stack must preserve the server cursor"));
        context.runOnClient(c -> {
            var editor = (ItemEditorScreen)c.screen;
            var path = List.<Object>of("components", "minecraft:container", 0, "item");
            c.setScreen(new ItemEditorScreen(editor.session(), editor, path));
            var data = editor.session().document;
            var changed = ((CompoundTag) StackData.at(data, path)).copy(); changed.putInt("count", 12);
            editor.session().edit(StackData.replace(data, path, changed));
        });
        click(context, "同步"); context.waitFor(c -> ((ItemEditorScreen)c.screen).session().status.equals("同步成功"));
        server.runOnServer(s -> {
            var box = s.getPlayerList().getPlayers().getFirst().containerMenu.getSlot(0).getItem();
            check(box.get(DataComponents.CONTAINER).stream().findFirst().orElseThrow().getCount() == 12, "Nested edit writes enclosing stack to original chest");
        });
        context.runOnClient(c -> {
            var session = ((ItemEditorScreen)c.screen).session(); var data = session.document.copy();
            data.getCompoundOrEmpty("components").put("missing_mod:unknown", new CompoundTag()); session.edit(data);
            check(!session.error.isEmpty(), "Unknown unregistered components must report errors, not get discarded");
            check(session.document.getCompoundOrEmpty("components").contains("missing_mod:unknown"), "Unknown input is retained in tree");
            session.sync(ok -> check(!ok, "Invalid component must not silently succeed"));
        });
        context.runOnClient(c -> {
            var session = ((ItemEditorScreen)c.screen).session(); var data = session.document.copy();
            data.getCompoundOrEmpty("components").remove("missing_mod:unknown"); data.putInt("count", 2); session.edit(data);
        });
        server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().closeContainer());
        context.waitFor(c -> c.screen instanceof ItemEditorScreen screen && !screen.session().valid);
        context.runOnClient(c -> {
            var session = ((ItemEditorScreen)c.screen).session(); var before = session.document.copy();
            session.sync(ok -> check(!ok, "Closed source cannot accept writes"));
            check(session.document.equals(before) && session.status.startsWith("同步失败"), "Failed sync preserves the draft and reports failure");
            c.screen.onClose();
        });
        context.waitForScreen(null);
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.STONE) == 5,
                "Server close must return the carried stack without duplication or loss"));
    }

    private void hover(ClientGameTestContext context, int index) {
        double[] pos = context.computeOnClient(c -> {
            var screen = (AbstractContainerScreen<?>) c.screen; var slot = screen.getMenu().getSlot(index);
            var bounds = (com.sinkie114.client.mixin.ContainerScreenAccessor) screen;
            return new double[]{(bounds.nbtmaker$left() + slot.x + 8.0) * c.getWindow().getScreenWidth() / screen.width,
                    (bounds.nbtmaker$top() + slot.y + 8.0) * c.getWindow().getScreenHeight() / screen.height};
        });
        context.getInput().setCursorPos(pos[0], pos[1]); context.waitTicks(2);
        context.runOnClient(c -> check(((com.sinkie114.client.mixin.ContainerScreenAccessor)c.screen).nbtmaker$hoveredSlot() != null,
                "Test cursor must hover an actual slot"));
    }
    private void click(ClientGameTestContext context, String label) {
        context.runOnClient(c -> {
            Button button = c.screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals(label))
                    .map(child -> (Button) child).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
            check(button.active, "Button must be enabled: " + label);
            var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0));
            c.screen.mouseClicked(event, false);
        }); context.waitTicks(2);
    }
}
