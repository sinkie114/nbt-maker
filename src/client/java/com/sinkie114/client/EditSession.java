package com.sinkie114.client;

import com.sinkie114.client.mixin.CreativeSlotAccessor;
import com.sinkie114.client.compat.SeeItemSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;
import java.util.function.Consumer;

/** No custom packets or remote edit channel. Every server access runs on the integrated server executor. */
public final class EditSession {
    public final Minecraft client;
    public final AbstractContainerScreen<?> source;
    public final AbstractContainerMenu clientMenu;
    public final boolean editable;
    public final String location;
    public final UUID playerId;
    private final Slot clientSlot;
    private final SeeItemSource seeSource;
    private SeeItemSource seeServerSource;
    private final int inventoryIndex, menuIndex;
    private final net.minecraft.client.multiplayer.ClientLevel level;
    private final net.minecraft.client.server.IntegratedServer server;
    private AbstractContainerMenu serverMenu;
    private Slot serverSlot;
    private boolean captured, pollPending;
    public boolean syncing, valid = true, changed;
    public CompoundTag document, baseline, observed;
    public ItemStack preview = ItemStack.EMPTY;
    public String error = "", status = "";
    public int revision;

    private EditSession(Minecraft c, AbstractContainerScreen<?> source, Slot slot) {
        this.client = c; this.source = source; this.clientMenu = c.player.containerMenu;
        this.level = c.level; this.server = c.getSingleplayerServer();
        playerId = c.player.getUUID();
        Slot unwrapped = slot instanceof CreativeSlotAccessor accessor ? accessor.nbtmaker$target() : slot;
        clientSlot = unwrapped;
        inventoryIndex = unwrapped.container == c.player.getInventory() ? unwrapped.getContainerSlot() : -1;
        menuIndex = source instanceof CreativeModeInventoryScreen ? -1 : source.getMenu().slots.indexOf(slot);
        seeSource = SeeItemSource.find(source.getMenu(), menuIndex);
        editable = server != null && (seeSource == null || seeSource.editable());
        location = seeSource != null ? seeSource.label() : source.getTitle().getString() + " / " + (inventoryIndex >= 0 ? "玩家背包 " + inventoryIndex : "容器槽 " + menuIndex)
                + " / " + unwrapped.container.getClass().getSimpleName();
        document = StackData.encode(slot.getItem().copy(), c.level.registryAccess());
        baseline = document.copy(); observed = baseline.copy();
        refresh();
    }

    public static EditSession open(Minecraft c, AbstractContainerScreen<?> source, Slot slot) { return new EditSession(c, source, slot); }

    public void edit(CompoundTag next) {
        if (!editable || syncing) return;
        document = next.copy(); revision++; status = ""; refresh();
    }

    private void refresh() {
        try { preview = StackData.decode(document, level.registryAccess()); error = ""; }
        catch (RuntimeException ex) { preview = ItemStack.EMPTY; error = ex.getMessage(); }
    }

    private ServerPlayer resolve() {
        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
        if (p == null || p.isRemoved() || !p.isAlive()) throw new IllegalStateException("玩家已离开或死亡");
        if (!captured) {
            captured = true; serverMenu = p.containerMenu;
            if (seeSource != null) {
                if (serverMenu.containerId != source.getMenu().containerId) throw new IllegalStateException("SEE 来源容器已关闭");
                seeServerSource = SeeItemSource.find(serverMenu, menuIndex);
                if (seeServerSource == null) throw new IllegalStateException("SEE 来源容器已失效");
            } else if (inventoryIndex >= 0) {
                for (Slot s : p.inventoryMenu.slots) if (s.container == p.getInventory() && s.getContainerSlot() == inventoryIndex) { serverSlot = s; break; }
            } else if (menuIndex >= 0 && serverMenu.containerId == source.getMenu().containerId && menuIndex < serverMenu.slots.size()) serverSlot = serverMenu.getSlot(menuIndex);
        }
        if (serverSlot == null && seeServerSource == null) throw new IllegalStateException("该展示物品没有可写回的实际槽位");
        if (p.containerMenu != serverMenu || !serverMenu.stillValid(p)) throw new IllegalStateException("来源容器已关闭或失效");
        return p;
    }

    public void tick() {
        if (client.player == null || client.level != level || client.player.containerMenu != clientMenu) { valid = false; changed = true; return; }
        if (!editable) {
            try { observed = StackData.encode(seeSource == null ? clientSlot.getItem() : seeSource.readClient(), level.registryAccess()); changed = !observed.equals(baseline); }
            catch (RuntimeException ex) { valid = false; }
            return;
        }
        if (pollPending || syncing || client.getSingleplayerServer() != server) return;
        pollPending = true;
        server.execute(() -> {
            CompoundTag data = null; String failure = null;
            try {
                ServerPlayer player = resolve();
                data = StackData.encode(seeServerSource == null ? serverSlot.getItem() : seeServerSource.readServer(player), server.registryAccess());
            }
            catch (RuntimeException ex) { failure = ex.getMessage(); }
            CompoundTag result = data; String reason = failure;
            client.execute(() -> {
                pollPending = false; valid = reason == null;
                if (result != null) { observed = result; changed = !observed.equals(baseline); }
                else changed = true;
            });
        });
    }

    public void sync(Consumer<Boolean> done) {
        if (!editable || syncing || client.getSingleplayerServer() != server) return;
        if (!error.isEmpty()) { status = "同步失败：" + error; done.accept(false); return; }
        if (document.equals(baseline)) { status = "没有待同步的修改"; done.accept(false); return; }
        if (client.level != level || client.player == null || client.player.containerMenu != clientMenu) {
            status = "同步失败：来源容器已关闭"; done.accept(false); return;
        }
        CompoundTag requested = document.copy(); syncing = true; status = "同步中…";
        server.execute(() -> {
            CompoundTag actual = null; String failure = null;
            try {
                ServerPlayer player = resolve();
                ItemStack replacement = StackData.decode(requested, server.registryAccess());
                ItemStack expected = replacement.copy();
                // Deliberately no old-item comparison, mayPlace check, or game-mode/OP check.
                ItemStack written;
                if (seeServerSource != null) written = seeServerSource.writeServer(player, replacement);
                else { serverSlot.set(replacement); written = serverSlot.getItem(); }
                if (!ItemStack.matches(expected, written)) throw new IllegalStateException("槽位未接受完整物品数据");
                player.getInventory().setChanged();
                serverMenu.broadcastChanges(); player.inventoryMenu.broadcastChanges();
                actual = StackData.encode(written, server.registryAccess());
            } catch (RuntimeException ex) { failure = ex.getMessage(); }
            CompoundTag result = actual; String reason = failure;
            client.execute(() -> {
                syncing = false;
                if (result != null) {
                    document = result.copy(); baseline = result.copy(); observed = result.copy();
                    changed = false; valid = true; revision++; refresh(); status = "同步成功";
                    done.accept(true);
                } else { status = "同步失败：" + reason; done.accept(false); }
            });
        });
    }

    public void close() {
        if (client.player != null && client.level == level && client.player.containerMenu == clientMenu) client.setScreen(source);
        else client.setScreen(null);
        NbtMakerClient.activeSession = null;
    }
}
