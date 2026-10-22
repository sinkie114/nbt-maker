package com.sinkie114.client;

import com.sinkie114.client.mixin.ContainerScreenAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/** /give copying, the item ID dialog, and creating an item in an empty slot. Starts and ends in simple mode on hotbar slot 0. */
final class CommandBasicsTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static EditSession session(Minecraft c) { return ((EditorLayer) c.screen).session(); }

    static void exercise(ClientGameTestContext ctx, TestServerContext server) {
        CompoundTag baseline = ctx.computeOnClient(c -> session(c).document.copy());
        giveCommand(ctx, server);
        basicItem(ctx, baseline);
        emptySlot(ctx, server);
    }

    private static void giveCommand(ClientGameTestContext ctx, TestServerContext server) {
        click(ctx, "复制 /give");
        String command = ctx.computeOnClient(c -> {
            check(session(c).status.startsWith("已复制 /give 命令"), "Copy reports success: " + session(c).status);
            return ItemGiveCommand.of(session(c).preview, c.level.registryAccess()).command();
        });
        check(command.startsWith("/give @s diamond_sword[") && command.contains("custom_data="), "Command lists changed components: " + command);
        ctx.runOnClient(c -> check(c.keyboardHandler.getClipboard().equals(command), "Clipboard holds the command"));
        // The game itself must accept the command and give back an identical item.
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            ItemStack original = player.getInventory().getItem(0).copy();
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withEntity(player), command);
            int given = -1;
            for (int i = 1; i < 36; i++) if (ItemStack.isSameItemSameComponents(player.getInventory().getItem(i), original)) given = i;
            check(given > 0, "/give must recreate the exact item from " + command);
            player.getInventory().setItem(given, ItemStack.EMPTY);
        });
        // Levels above 255 only exist in the editor's format, so the copy must warn.
        ctx.runOnClient(c -> {
            try {
                var root = TagParser.parseCompoundFully("{id:\"minecraft:book\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:sharpness\":1000}}}");
                var result = ItemGiveCommand.of(StackData.decode(root, c.level.registryAccess()), c.level.registryAccess());
                check(!result.lossless() && result.command().contains("1000"), "Out-of-range level is kept and flagged: " + result);
                check(ItemGiveCommand.copiedMessage(result).contains("可能报错"), "Message warns about the level");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) { throw new AssertionError(ex); }
        });
    }

    private static void basicItem(ClientGameTestContext ctx, CompoundTag baseline) {
        click(ctx, "物品基础");
        ctx.runOnClient(c -> {
            check(c.screen instanceof SimpleComponentScreen.BasicItemScreen, "物品基础 opens the ID dialog");
            check(field(c, "物品 ID").getValue().equals("minecraft:diamond_sword"), "Dialog shows the current ID");
            // Longer than the 32 characters an EditBox keeps by default.
            field(c, "物品 ID").setValue("minecraft:light_blue_glazed_terracotta");
            check(field(c, "物品 ID").getValue().equals("minecraft:light_blue_glazed_terracotta"), "Long IDs are not cut off");
            field(c, "数量").setValue("abc");
        });
        click(ctx, "确定");
        ctx.runOnClient(c -> {
            check(c.screen instanceof SimpleComponentScreen.BasicItemScreen basic && basic.error().contains("整数"), "Bad count stays open with a visible error");
            field(c, "物品 ID").setValue("minecraft:not_an_item"); field(c, "数量").setValue("2");
        });
        click(ctx, "确定");
        ctx.runOnClient(c -> {
            check(((SimpleComponentScreen.BasicItemScreen) c.screen).error().contains("未注册"), "Unknown item is reported");
            field(c, "物品 ID").setValue("light_blue_glazed_terracotta");
        });
        click(ctx, "确定");
        ctx.runOnClient(c -> {
            check(c.screen instanceof SimpleComponentScreen, "Valid input returns to simple mode");
            check(session(c).document.getStringOr("id", "").equals("minecraft:light_blue_glazed_terracotta"), "ID gets the minecraft namespace");
            check(session(c).document.getIntOr("count", 0) == 2, "Count saved");
        });
        // Picking from the list keeps the count typed before opening it.
        click(ctx, "物品基础");
        ctx.runOnClient(c -> field(c, "数量").setValue("1"));
        click(ctx, "选择物品");
        ctx.runOnClient(c -> field(c, "搜索选项").setValue("minecraft:diamond_sword"));
        ctx.waitTick();
        clickContains(ctx, " · minecraft:diamond_sword");
        ctx.runOnClient(c -> {
            check(field(c, "物品 ID").getValue().equals("minecraft:diamond_sword"), "Picker fills the ID");
            check(field(c, "数量").getValue().equals("1"), "Picker keeps the typed count");
        });
        click(ctx, "确定");
        ctx.runOnClient(c -> check(session(c).document.equals(baseline), "Changing the ID and back keeps every component"));
    }

    private static void emptySlot(ClientGameTestContext ctx, TestServerContext server) {
        click(ctx, "关闭");
        ctx.waitForScreen(InventoryScreen.class);
        hover(ctx, 37); // hotbar slot 1, empty
        ctx.getInput().pressKey(GLFW.GLFW_KEY_X);
        ctx.waitForScreen(SimpleComponentScreen.BasicItemScreen.class);
        click(ctx, "选择物品");
        ctx.runOnClient(c -> field(c, "搜索选项").setValue("minecraft:apple"));
        ctx.waitTick();
        clickContains(ctx, " · minecraft:apple");
        ctx.runOnClient(c -> field(c, "数量").setValue("3"));
        click(ctx, "确定");
        ctx.runOnClient(c -> check(session(c).preview.is(Items.APPLE) && session(c).preview.getCount() == 3, "New item previews before sync"));
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(1).isEmpty(), "Nothing is written before sync"));
        click(ctx, "同步");
        ctx.waitFor(c -> session(c).status.equals("同步成功"));
        server.runOnServer(s -> {
            var inventory = s.getPlayerList().getPlayers().getFirst().getInventory();
            check(inventory.getItem(1).is(Items.APPLE) && inventory.getItem(1).getCount() == 3, "Sync places the new item in the empty slot");
            ItemStack.CODEC.encodeStart(s.registryAccess().createSerializationContext(NbtOps.INSTANCE), inventory.getItem(1)).getOrThrow();
            inventory.setItem(1, ItemStack.EMPTY);
        });
        ctx.waitFor(c -> c.player.getInventory().getItem(1).isEmpty());
        click(ctx, "关闭");
        ctx.waitForScreen(InventoryScreen.class);
        // Reopen the sword so the remaining suite continues where it left off.
        hover(ctx, 36);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_X);
        ctx.waitForScreen(SimpleComponentScreen.class);
    }

    // ---------- helpers ----------
    private static void hover(ClientGameTestContext ctx, int index) {
        double[] pos = ctx.computeOnClient(c -> {
            var screen = (AbstractContainerScreen<?>) c.screen; var slot = screen.getMenu().getSlot(index);
            var bounds = (ContainerScreenAccessor) screen;
            return new double[]{(bounds.nbtmaker$left() + slot.x + 8.0) * c.getWindow().getScreenWidth() / screen.width,
                    (bounds.nbtmaker$top() + slot.y + 8.0) * c.getWindow().getScreenHeight() / screen.height};
        });
        ctx.getInput().setCursorPos(pos[0], pos[1]); ctx.waitTicks(2);
        ctx.runOnClient(c -> check(((ContainerScreenAccessor) c.screen).nbtmaker$hoveredSlot() != null, "Test cursor must hover an actual slot"));
    }
    private static EditBox field(Minecraft c, String label) {
        return c.screen.children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals(label)).map(w -> (EditBox) w)
                .findFirst().orElseThrow(() -> new AssertionError("Missing input: " + label + " on " + c.screen.getClass().getSimpleName()));
    }
    private static void click(ClientGameTestContext ctx, String label) { click(ctx, label, false); }
    private static void clickContains(ClientGameTestContext ctx, String label) { click(ctx, label, true); }
    private static void click(ClientGameTestContext ctx, String label, boolean contains) {
        ctx.runOnClient(c -> {
            var b = c.screen.children().stream().filter(w -> w instanceof Button button && (contains ? button.getMessage().getString().contains(label) : button.getMessage().getString().equals(label)))
                    .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label + " on " + c.screen.getClass().getSimpleName()));
            check(b.active, "Button enabled: " + label);
            c.screen.mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
        });
        ctx.waitTicks(2);
    }
}
