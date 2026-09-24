package com.sinkie114.client;

import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemUseAnimation;
import java.util.*;
import static com.sinkie114.client.OptionPickerScreen.Option;

final class RegistryOptions {
    private RegistryOptions() {}
    static List<Option> enchantments(EditSession session) {
        return session.client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
                .map(h -> new Option(h.key().identifier().toString(), h.value().description().getString())).sorted(Comparator.comparing(Option::id)).toList();
    }
    static List<Option> effects(EditSession session) {
        return session.client.level.registryAccess().lookupOrThrow(Registries.MOB_EFFECT).listElements()
                .map(h -> {
                    String id=h.key().identifier().toString(), name=h.value().getDisplayName().getString();
                    return new Option(id,name.equals(h.value().getDescriptionId())?id:name);
                }).sorted(Comparator.comparing(Option::id)).toList();
    }
    static List<Option> potions(EditSession session) {
        return session.client.level.registryAccess().lookupOrThrow(Registries.POTION).listElements().map(h -> {
            String key = "item.minecraft.potion.effect." + h.value().name();
            String name = Component.translatable(key).getString();
            String details = h.value().getEffects().stream().map(e -> e.getEffect().value().getDisplayName().getString()
                    + " " + (e.getAmplifier()+1) + " · " + e.getDuration()/20.0 + " 秒").reduce((a,b) -> a + "\n" + b).orElse("无基础效果");
            return new Option(h.key().identifier().toString(), name.equals(key) ? h.key().identifier().toString() : name, details);
        }).sorted(Comparator.comparing(Option::id)).toList();
    }
    static List<Option> items() {
        return BuiltInRegistries.ITEM.keySet().stream().filter(id -> !id.equals(Identifier.withDefaultNamespace("air")))
                .sorted().map(id -> new Option(id.toString(), BuiltInRegistries.ITEM.get(id).orElseThrow().value().getName().getString())).toList();
    }
    static List<Option> sounds() {
        return BuiltInRegistries.SOUND_EVENT.keySet().stream().sorted().map(id -> new Option(id.toString(), id.getPath())).toList();
    }
    static List<Option> animations() {
        return Arrays.stream(ItemUseAnimation.values()).map(a -> new Option(a.getSerializedName(), switch (a.getSerializedName()) {
            case "eat" -> "吃"; case "drink" -> "喝"; case "none" -> "无动作"; case "block" -> "格挡";
            case "bow" -> "拉弓"; case "trident" -> "三叉戟"; case "crossbow" -> "弩"; case "spyglass" -> "望远镜";
            case "toot_horn" -> "吹角"; case "brush" -> "刷扫"; case "bundle" -> "收纳袋"; case "spear" -> "长矛";
            default -> a.getSerializedName();
        })).toList();
    }
    static List<Option> withNone(List<Option> options, String label) {
        List<Option> result = new ArrayList<>(); result.add(new Option("", label)); result.addAll(options); return result;
    }
    static String name(List<Option> options, String id) { return options.stream().filter(o -> o.id().equals(id)).map(Option::name).findFirst().orElse(id); }
}
