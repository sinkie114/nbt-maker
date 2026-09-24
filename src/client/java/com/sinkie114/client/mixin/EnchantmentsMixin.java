package com.sinkie114.client.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Constructor access only. Vanilla validation, CODEC and STREAM_CODEC are unchanged. */
@Mixin(ItemEnchantments.class)
public interface EnchantmentsMixin {
    @Invoker("<init>")
    static ItemEnchantments nbtmaker$create(Object2IntOpenHashMap<Holder<Enchantment>> levels) {
        throw new AssertionError();
    }
}
