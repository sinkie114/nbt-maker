package com.sinkie114.client;

import com.sinkie114.client.mixin.ContainerScreenAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.UUID;

/** Runs only with -PseeCompatJar=...; asserts writes against real entities after later server ticks. */
public final class SeeIntegrationTest implements FabricClientGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            UUID id = server.computeOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst(); p.setGameMode(GameType.SURVIVAL);
                var villager = EntityType.VILLAGER.create(p.level(), EntitySpawnReason.COMMAND);
                villager.setPos(p.getX() + 2, p.getY(), p.getZ()); villager.setNoAi(true);
                var sword = new ItemStack(Items.DIAMOND_SWORD); sword.set(DataComponents.CUSTOM_NAME, Component.literal("Before"));
                villager.setItemSlot(EquipmentSlot.MAINHAND, sword); p.level().addFreshEntity(villager);
                p.getInventory().setItem(1, new ItemStack(Items.APPLE, 5));
                return villager.getUUID();
            });
            int entityId = server.computeOnServer(s -> s.overworld().getEntity(id).getId());
            context.waitFor(c -> c.level.getEntity(entityId) != null);
            openSee(context, entityId); openItem(context, "equipment.mainhand");
            context.runOnClient(c -> {
                var session = editor(c).session(); check(session.location.contains("equipment.mainhand"), "Location identifies the real equipment slot");
                var doc = session.document.copy(); doc.getCompoundOrEmpty("components").putString("minecraft:custom_name", "Linked sword");
                // SEE's live packet regression uses vanilla-serializable levels; signed-int memory tests run separately.
                var enchantments = new CompoundTag(); enchantments.putInt("minecraft:sharpness", 255);
                doc.getCompoundOrEmpty("components").put("minecraft:enchantments", enchantments); session.edit(doc);
            });
            server.runOnServer(s -> check(((Villager)s.overworld().getEntity(id)).getMainHandItem().getHoverName().getString().equals("Before"), "SEE editing must remain a draft before sync"));
            sync(context); context.waitTicks(5);
            server.runOnServer(s -> {
                var villager = (Villager)s.overworld().getEntity(id);
                check(villager.getMainHandItem().getHoverName().getString().equals("Linked sword"), "Sync reaches actual equipment, not only the menu cache");
                check(((VillagerHandProtection)villager).see$isMainHandProtected(), "Linked write must preserve SEE villager hand protection");
            });
            context.runOnClient(c -> check(!editor(c).session().changed, "SEE cache refresh must not undo the sync or dirty the baseline"));
            context.takeScreenshot("see-nbt-equipment");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(EntityDebugScreen.class);
            context.runOnClient(c -> check(find(((EntityDebugScreen)c.screen).getMenu(), "equipment.mainhand").getItem().getHoverName().getString().equals("Linked sword"), "Returning to SEE keeps the same menu and updated item"));
            // Player slots in SEE's information tabs must also map to the real inventory.
            click(context, "概览"); openItem(context, "player.1");
            context.runOnClient(c -> { var session = editor(c).session(); var doc = session.document.copy(); doc.putInt("count", 9); session.edit(doc); });
            sync(context);
            server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(1).getCount() == 9, "SEE player inventory index is preserved"));
            closeBoth(context);
            testReadOnly(context, server, entityId, id);
            testContainer(context, server);
            testDroppedItem(context, server);
        }
    }

    private void testReadOnly(ClientGameTestContext context, TestServerContext server, int entityId, UUID id) {
        // Exercise SEE's exact remote-view path without needing an external server.
        context.runOnClient(c -> c.setScreen(EntityDebugScreen.readOnly(c.level.getEntity(entityId), c.player.getInventory())));
        context.waitTicks(2); openItem(context, "equipment.mainhand");
        context.runOnClient(c -> {
            var session = editor(c).session(); check(!session.editable, "The source's read-only mode must be respected");
            check(c.screen.children().stream().noneMatch(child -> child instanceof Button b && b.getMessage().getString().equals("同步")), "Read-only SEE has no sync button");
            var before = session.document.copy(); var changed = before.copy(); changed.putInt("count", 99); session.edit(changed); session.sync(ok -> {});
            check(session.document.equals(before), "Even direct edit/sync calls cannot change a read-only draft");
        });
        click(context, "简单模式"); click(context, "属性"); click(context, "属性修饰符");
        context.runOnClient(c -> {
            check(c.screen instanceof AttributeModifierScreen, "Read-only source can inspect the attribute list");
            for (var child : c.screen.children()) if (child instanceof Button b &&
                    (b.getMessage().getString().equals("新增修饰符") || b.getMessage().getString().equals("删除")))
                check(!b.active, "Read-only attribute mutation controls are disabled");
        });
        click(context, "查看");
        context.runOnClient(c -> {
            for (var child : c.screen.children()) if (child instanceof Button b && !b.getMessage().getString().equals("取消"))
                check(!b.active, "Read-only attribute form cannot change options or save");
        });
        click(context, "取消"); click(context, "返回");
        FoodPotionEditorTest.readOnly(context);
        click(context, "高级模式");
        click(context, "SNBT");
        context.runOnClient(c -> {
            for (var child : c.screen.children()) if (child instanceof Button b && (b.getMessage().getString().equals("导入文件") || b.getMessage().getString().equals("粘贴 SNBT")))
                check(!b.active, "Read-only import is disabled");
        });
        click(context, "返回");
        server.runOnServer(s -> ((Villager)s.overworld().getEntity(id)).setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD)));
        context.waitFor(c -> editor(c).session().changed);
        context.runOnClient(c -> check(editor(c).session().observed.getStringOr("id", "").equals("minecraft:golden_sword"), "Read-only inspection remains live while SEE is suspended"));
        context.takeScreenshot("see-nbt-read-only");
        closeBoth(context);
    }

    private void testContainer(ClientGameTestContext context, TestServerContext server) {
        UUID id = server.computeOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst(); var cart = EntityType.CHEST_MINECART.create(p.level(), EntitySpawnReason.COMMAND);
            cart.setPos(p.getX() + 2, p.getY(), p.getZ());
            try {
                var doc = TagParser.parseCompoundFully("{id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":[{slot:0,item:{id:\"minecraft:diamond\",count:2}}]}}");
                cart.setItem(26, StackData.decode(doc, s.registryAccess()));
            } catch (Exception ex) { throw new AssertionError(ex); }
            p.level().addFreshEntity(cart); return cart.getUUID();
        });
        int entityId = server.computeOnServer(s -> s.overworld().getEntity(id).getId());
        context.waitFor(c -> c.level.getEntity(entityId) != null); openSee(context, entityId);
        context.runOnClient(c -> { var menu = ((EntityDebugScreen)c.screen).getMenu(); menu.otherPage = 2; menu.layout(8,18,85); });
        openItem(context, "inventory.26");
        context.runOnClient(c -> {
            var parent = editor(c); var path = List.<Object>of("components", "minecraft:container", 0, "item");
            c.setScreen(new ItemEditorScreen(parent.session(), parent, path));
            var child = ((CompoundTag)StackData.at(parent.session().document, path)).copy(); child.putInt("count", 13);
            parent.session().edit(StackData.replace(parent.session().document, path, child));
        });
        sync(context); context.waitTicks(4);
        server.runOnServer(s -> {
            var cart = (MinecartChest)s.overworld().getEntity(id);
            check(cart.getItem(26).get(DataComponents.CONTAINER).stream().findFirst().orElseThrow().getCount() == 13, "Nested data writes to slot 26 on SEE's third page");
            check(cart.getItem(0).isEmpty(), "Page selection must not redirect to a similar visible index");
            cart.setItem(26, new ItemStack(Items.DIRT, 3));
        });
        context.waitFor(c -> editor(c).session().changed);
        context.runOnClient(c -> { var session = editor(c).session(); var doc = session.document.copy(); doc.getCompoundOrEmpty("components").putString("minecraft:custom_name", "Last writer"); session.edit(doc); });
        sync(context);
        server.runOnServer(s -> check(((MinecartChest)s.overworld().getEntity(id)).getItem(26).is(Items.SHULKER_BOX), "External changes warn but do not prevent overwriting the recorded entity slot"));
        server.runOnServer(s -> s.overworld().getEntity(id).discard());
        context.waitFor(c -> c.screen instanceof ItemEditorScreen screen && !screen.session().valid);
        context.runOnClient(c -> {
            var session = editor(c).session(); var doc = session.document.copy(); doc.putInt("count", 2); session.edit(doc);
            session.sync(ok -> check(!ok, "Removed entity must reject writes"));
        });
        context.waitFor(c -> editor(c).session().status.startsWith("同步失败"));
        closeBoth(context);
    }

    private void testDroppedItem(ClientGameTestContext context, TestServerContext server) {
        UUID id = server.computeOnServer(s -> {
            var p = s.getPlayerList().getPlayers().getFirst();
            var entity = new ItemEntity(p.level(), p.getX() + 2, p.getY(), p.getZ(), new ItemStack(Items.DIAMOND, 3));
            entity.setNoGravity(true); entity.setPickUpDelay(32767); p.level().addFreshEntity(entity); return entity.getUUID();
        });
        int entityId = server.computeOnServer(s -> s.overworld().getEntity(id).getId());
        context.waitFor(c -> c.level.getEntity(entityId) != null); openSee(context, entityId); openItem(context, "item");
        context.runOnClient(c -> { var session = editor(c).session(); var doc = session.document.copy(); doc.putInt("count", 120); session.edit(doc); });
        click(context, "同步"); click(context, "确定同步");
        context.waitFor(c -> c.screen instanceof ItemEditorScreen screen && screen.session().status.equals("同步成功"));
        context.waitTicks(4);
        server.runOnServer(s -> check(((ItemEntity)s.overworld().getEntity(id)).getItem().getCount() == 120, "Direct item-entity setter preserves unusual counts"));
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(EntityDebugScreen.class);
        context.runOnClient(c -> check(find(((EntityDebugScreen)c.screen).getMenu(), "item").getItem().getCount() == 120, "SEE's network cache must not truncate the actual stack"));
        openItem(context, "item");
        context.runOnClient(c -> { var session = editor(c).session(); check(session.document.getIntOr("count", 0) == 120, "Reopening reads the full count"); session.edit(new CompoundTag()); });
        sync(context);
        context.waitFor(c -> c.level.getEntity(entityId) == null);
        closeBoth(context);
    }

    private static ItemEditorScreen editor(net.minecraft.client.Minecraft c) { return (ItemEditorScreen)c.screen; }
    private void openSee(ClientGameTestContext context, int entityId) {
        context.runOnClient(c -> EntityEditBridge.open(c, c.level.getEntity(entityId)));
        context.waitFor(c -> c.screen instanceof EntityDebugScreen screen && screen.getMenu().ready);
    }
    private void closeBoth(ClientGameTestContext context) {
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(EntityDebugScreen.class);
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null); context.waitTicks(3);
    }
    private Slot find(EntityDebugMenu menu, String key) {
        if (key.startsWith("player.")) {
            int index = Integer.parseInt(key.substring(7));
            return menu.slots.stream().skip(menu.descriptions.size()).filter(slot -> slot.getContainerSlot() == index).findFirst().orElseThrow();
        }
        for (int i = 0; i < menu.descriptions.size(); i++) if (menu.descriptions.get(i).key().equals(key)) return menu.getSlot(i);
        throw new AssertionError("Missing SEE slot " + key);
    }
    private void openItem(ClientGameTestContext context, String key) {
        double[] pos = context.computeOnClient(c -> {
            var source = (EntityDebugScreen)c.screen; Slot slot = find(source.getMenu(), key); check(slot.isActive(), "Target slot must be visible");
            var access = (ContainerScreenAccessor)(Object)source;
            return new double[]{(access.nbtmaker$left() + slot.x + 8.0) * c.getWindow().getScreenWidth() / source.width,
                    (access.nbtmaker$top() + slot.y + 8.0) * c.getWindow().getScreenHeight() / source.height};
        });
        context.getInput().setCursorPos(pos[0], pos[1]); context.waitTicks(2); context.getInput().pressKey(NbtMakerClient.OPEN);
        context.waitForScreen(SimpleComponentScreen.class);
        click(context, "高级模式"); context.waitForScreen(ItemEditorScreen.class);
    }
    private void sync(ClientGameTestContext context) {
        click(context, "同步"); context.waitFor(c -> c.screen instanceof ItemEditorScreen screen && screen.session().status.equals("同步成功"));
    }
    private void click(ClientGameTestContext context, String label) {
        context.runOnClient(c -> {
            var button = c.screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals(label))
                    .map(child -> (Button)child).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
            check(button.active, "Button must be enabled: " + label);
            c.screen.mouseClicked(new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0,0)), false);
        }); context.waitTicks(2);
    }
}
