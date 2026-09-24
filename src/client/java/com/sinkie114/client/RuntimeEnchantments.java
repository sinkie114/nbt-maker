package com.sinkie114.client;

import com.sinkie114.client.mixin.EnchantmentsMixin;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Editor snapshots only; never registered as a persistence or packet codec. */
final class RuntimeEnchantments {
    private RuntimeEnchantments() {}

    static ItemEnchantments read(Tag tag, HolderLookup.Provider registries) {
        if (!(tag instanceof CompoundTag data)) throw new IllegalArgumentException("附魔组件必须是 Compound");
        var registry = registries.lookupOrThrow(Registries.ENCHANTMENT);
        var levels = new Object2IntOpenHashMap<Holder<Enchantment>>();
        // Construct with an empty, privately owned map, then fill it before publishing the component.
        // Never mutate EMPTY, an item default, a draft, or any existing stack's component.
        ItemEnchantments result = EnchantmentsMixin.nbtmaker$create(levels);
        for (String id : data.keySet()) {
            var holder = registry.get(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse(id)))
                    .orElseThrow(() -> new IllegalArgumentException("未注册的附魔：" + id));
            Tag value = data.get(id);
            if (!(value instanceof NumericTag number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < Integer.MIN_VALUE || number.doubleValue() > Integer.MAX_VALUE
                    || number.doubleValue() != Math.rint(number.doubleValue()))
                throw new IllegalArgumentException("附魔等级必须是 -2147483648 至 2147483647 的整数：" + id);
            if (levels.containsKey(holder)) throw new IllegalArgumentException("重复的附魔：" + id);
            levels.put(holder, number.intValue());
        }
        return result;
    }

    static CompoundTag write(ItemEnchantments enchantments) {
        var data = new CompoundTag();
        for (var entry : enchantments.entrySet()) {
            var key = entry.getKey().unwrapKey().orElseThrow(() -> new IllegalArgumentException("附魔没有注册 ID"));
            data.putInt(key.identifier().toString(), entry.getIntValue());
        }
        return data;
    }

    static int parseLevel(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("附魔等级必须是 -2147483648 至 2147483647 的整数。");
    }
}
