package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Files;

/** Undo/redo history and the local SNBT library, starting and ending on the top-level simple editor. */
final class HistoryLibraryTest {
    private HistoryLibraryTest() {}
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void exercise(ClientGameTestContext ctx, TestServerContext server) {
        CompoundTag start = ctx.computeOnClient(c -> session(c).document.copy());
        ctx.runOnClient(c -> {
            var session = session(c);
            CompoundTag changed = start.copy(); changed.putInt("count", 7); session.edit(changed);
            check(session.canUndo() && !session.canRedo(), "A new edit is undoable and clears redo");
            session.undo();
            check(session.document.equals(start), "Undo restores the previous copy");
            check(session.canRedo(), "Undo makes redo available");
            session.redo();
            check(session.document.getIntOr("count", 0) == 7, "Redo reapplies the edit");
            c.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL));
            check(session.document.equals(start), "Ctrl+Z undoes from the simple editor");
            c.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_Y, 0, GLFW.GLFW_MOD_CONTROL));
            check(session.document.getIntOr("count", 0) == 7, "Ctrl+Y redoes from the simple editor");
            session.edit(start);
        });
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).getCount() == 1, "History never writes the real item"));

        String name = "nbt-maker-test-" + System.nanoTime();
        click(ctx, "收藏夹");
        ctx.waitForScreen(LibraryScreen.class);
        ctx.runOnClient(c -> c.screen.children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals("收藏名称"))
                .map(w -> (EditBox) w).findFirst().orElseThrow(() -> new AssertionError("Missing library name input")).setValue(name));
        click(ctx, "保存当前物品");
        ctx.runOnClient(c -> check(Files.isRegularFile(LibraryScreen.directory(c).resolve(name + ".snbt")), "Saving writes an SNBT file"));
        ctx.runOnClient(c -> { CompoundTag changed = start.copy(); changed.putString("id", "minecraft:apple"); session(c).edit(changed); });
        click(ctx, name);
        ctx.waitForScreen(SimpleComponentScreen.class);
        ctx.runOnClient(c -> {
            var session = session(c);
            check(session.preview.is(Items.DIAMOND_SWORD), "Loading a favourite replaces the copy: " + session.document);
            session.undo();
            check(session.preview.is(Items.APPLE), "Loading a favourite is undoable");
            session.edit(start);
            try { Files.deleteIfExists(LibraryScreen.directory(c).resolve(name + ".snbt")); }
            catch (java.io.IOException ex) { throw new AssertionError(ex); }
        });
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).is(Items.DIAMOND_SWORD), "The library never writes the real item"));
    }

    private static EditSession session(Minecraft c) { return ((EditorLayer) c.screen).session(); }

    private static void click(ClientGameTestContext ctx, String label) {
        ctx.runOnClient(c -> {
            Button button = c.screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals(label))
                    .map(child -> (Button) child).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
            check(button.active, "Button must be enabled: " + label);
            var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0));
            c.screen.mouseClicked(event, false);
        });
        ctx.waitTicks(2);
    }
}
