package com.sinkie114.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sinkie114.client.mixin.ContainerScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class NbtMakerClient implements ClientModInitializer {
    public static KeyMapping OPEN;
    public static Screen suspending;
    public static EditSession activeSession;

    @Override public void onInitializeClient() {
        OPEN = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.nbt-maker.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X, KeyMapping.Category.register(Identifier.parse("nbt-maker:editor"))));
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?> container) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> {
                    if (!OPEN.matches(key) || typing(s)) return true;
                    var slot = ((ContainerScreenAccessor) container).nbtmaker$hoveredSlot();
                    if (slot == null || !slot.isActive()) return true;
                    // An empty slot opens a new item, but only where it can be written back and nothing is carried.
                    boolean create = !slot.hasItem();
                    if (create && (client.getSingleplayerServer() == null || !container.getMenu().getCarried().isEmpty())) return true;
                    try {
                        var session = EditSession.open(client, container, slot);
                        if (create && !session.canWriteBack()) return true;
                        // New users start in the form-based editor; the complete tree remains one click away.
                        var editor = new SimpleComponentScreen(session, null);
                        suspending = screen;
                        activeSession = session;
                        client.setScreen(editor);
                        if (create) {
                            session.status = "空格子：选择物品后点击“同步”放入";
                            editor.openBasic();
                        }
                    } catch (RuntimeException ex) {
                        if (client.player != null) client.player.displayClientMessage(Component.literal("无法打开物品数据：" + ex.getMessage()), true);
                    } finally { suspending = null; }
                    return false;
                });
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (activeSession != null) {
                if (client.screen instanceof EditorLayer layer && layer.session() == activeSession) activeSession.tick();
                else activeSession = null;
            }
        });
    }

    /** True while a text box on the screen (such as the creative search) has focus, so the key types a letter. */
    private static boolean typing(Screen screen) {
        for (var child : screen.children()) if (child instanceof EditBox box && box.isFocused()) return true;
        return false;
    }
}
