package com.sinkie114.client.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.function.Function;

/**
 * 网络解码不再校验附魔等级（原版限制 0–255）。
 * 只替换 STREAM_CODEC 的构造函数参数；存档用的 CODEC（1–255）保持原样。
 */
@Mixin(ItemEnchantments.class)
public class EnchantmentStreamMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/codec/StreamCodec;composite(Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Ljava/util/function/Function;)Lnet/minecraft/network/codec/StreamCodec;"),
            index = 2)
    private static Function<Object2IntOpenHashMap<Holder<Enchantment>>, ItemEnchantments> nbtmaker$lenientDecode(
            Function<Object2IntOpenHashMap<Holder<Enchantment>>, ItemEnchantments> original) {
        return decoded -> {
            // 构造时传入空表（校验循环什么都不查），再把解码结果填进这张私有表。
            var levels = new Object2IntOpenHashMap<Holder<Enchantment>>();
            ItemEnchantments result = EnchantmentsMixin.nbtmaker$create(levels);
            levels.putAll(decoded);
            return result;
        };
    }
}
