package com.sinkie114.client.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional adapter to SEE's public API; neither mod requires the other to be installed. */
public final class SeeItemSource {
    private record Api(Method editable, Method label, Method readClient, Method readServer, Method writeServer) {}
    private static final ClassValue<Api> APIS = new ClassValue<>() {
        @Override protected Api computeValue(Class<?> type) {
            try {
                return new Api(type.getMethod("see$nbtEditable"), type.getMethod("see$nbtSourceLabel", int.class), type.getMethod("see$nbtReadClient", int.class),
                        type.getMethod("see$nbtReadServer", ServerPlayer.class, int.class),
                        type.getMethod("see$nbtWriteServer", ServerPlayer.class, int.class, ItemStack.class));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("SEE 联动需要 SEE 1.0.7 或更高版本", ex);
            }
        }
    };
    private final AbstractContainerMenu menu;
    private final int index;
    private final Api api;

    private SeeItemSource(AbstractContainerMenu menu, int index) {
        this.menu = menu; this.index = index; this.api = APIS.get(menu.getClass());
    }
    public static SeeItemSource find(AbstractContainerMenu menu, int index) {
        return menu.getClass().getName().equals("com.sinkie114.client.EntityDebugMenu") ? new SeeItemSource(menu, index) : null;
    }
    public String label() { return (String) invoke(api.label, index); }
    public boolean editable() { return (boolean) invoke(api.editable); }
    public ItemStack readClient() { return (ItemStack) invoke(api.readClient, index); }
    public ItemStack readServer(ServerPlayer player) { return (ItemStack) invoke(api.readServer, player, index); }
    public ItemStack writeServer(ServerPlayer player, ItemStack item) { return (ItemStack) invoke(api.writeServer, player, index, item); }
    private Object invoke(Method method, Object... args) {
        try { return method.invoke(menu, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("SEE 物品访问失败", ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("SEE 联动接口不可用", ex); }
    }
}
