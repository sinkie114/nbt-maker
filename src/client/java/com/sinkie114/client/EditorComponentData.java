package com.sinkie114.client;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import java.util.*;

/** GUI document conversion, including nested items. No vanilla codec is replaced or redirected. */
final class EditorComponentData {
    private EditorComponentData() {}

    static boolean handles(DataComponentType<?> type) {
        return type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS
                || type == DataComponents.CONTAINER || type == DataComponents.BUNDLE_CONTENTS
                || type == DataComponents.CHARGED_PROJECTILES || type == DataComponents.USE_REMAINDER;
    }

    static <T> Tag write(DataComponentType<T> type, T value, HolderLookup.Provider registries) {
        if (type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS)
            return RuntimeEnchantments.write((ItemEnchantments)value);
        if (type == DataComponents.CONTAINER) {
            var result = new ListTag();
            var items = ((ItemContainerContents)value).stream().toList();
            for (int slot = 0; slot < items.size(); slot++) {
                if (items.get(slot).isEmpty()) continue;
                var entry = new CompoundTag(); entry.putInt("slot", slot);
                entry.put("item", StackData.encode(items.get(slot), registries)); result.add(entry);
            }
            return result;
        }
        if (type == DataComponents.BUNDLE_CONTENTS) return writeItems(((BundleContents)value).items(), registries);
        if (type == DataComponents.CHARGED_PROJECTILES) return writeItems(((ChargedProjectiles)value).getItems(), registries);
        if (type == DataComponents.USE_REMAINDER) return StackData.encode(((UseRemainder)value).convertInto(), registries);
        return type.codecOrThrow().encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value).getOrThrow();
    }

    static <T> void apply(ItemStack stack, DataComponentType<T> type, Tag value, HolderLookup.Provider registries) {
        stack.set(type, read(type, value, registries));
    }

    @SuppressWarnings("unchecked") // Each branch pairs a known component type with its exact runtime value.
    static <T> T read(DataComponentType<T> type, Tag value, HolderLookup.Provider registries) {
        if (type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS)
            return (T)RuntimeEnchantments.read(value, registries);
        if (type == DataComponents.CONTAINER) {
            var items = new ArrayList<ItemStack>();
            for (Tag element : list(value)) {
                if (!(element instanceof CompoundTag entry)) throw new IllegalArgumentException("容器条目必须是 Compound");
                Tag slotTag = entry.get("slot");
                if (slotTag == null) throw new IllegalArgumentException("容器条目缺少 slot");
                int slot = Codec.intRange(0,255).parse(NbtOps.INSTANCE, slotTag).getOrThrow();
                while (items.size() <= slot) items.add(ItemStack.EMPTY);
                items.set(slot, readItem(entry.get("item"), registries));
            }
            return (T)ItemContainerContents.fromItems(items);
        }
        if (type == DataComponents.BUNDLE_CONTENTS) return (T)new BundleContents(readItems(value, registries));
        if (type == DataComponents.CHARGED_PROJECTILES) return (T)ChargedProjectiles.of(readItems(value, registries));
        if (type == DataComponents.USE_REMAINDER) return (T)new UseRemainder(readItem(value, registries));
        return type.codecOrThrow().parse(registries.createSerializationContext(NbtOps.INSTANCE), value).getOrThrow();
    }

    private static ListTag writeItems(Iterable<ItemStack> items, HolderLookup.Provider registries) {
        var result = new ListTag();
        for (ItemStack item : items) result.add(StackData.encode(item, registries));
        return result;
    }

    private static List<ItemStack> readItems(Tag value, HolderLookup.Provider registries) {
        var items = new ArrayList<ItemStack>();
        for (Tag element : list(value)) items.add(readItem(element, registries));
        return List.copyOf(items);
    }

    private static ListTag list(Tag value) {
        if (!(value instanceof ListTag list)) throw new IllegalArgumentException("物品列表必须是 List");
        return list;
    }

    private static ItemStack readItem(Tag value, HolderLookup.Provider registries) {
        if (!(value instanceof CompoundTag data)) throw new IllegalArgumentException("内部物品必须是 Compound");
        ItemStack item = StackData.decode(data, registries);
        if (item.isEmpty()) throw new IllegalArgumentException("内部物品不能为空，请删除对应条目");
        return item;
    }
}
