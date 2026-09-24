package com.sinkie114.client;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;

/** Test-only mod attribute, deliberately absent from the editor's source and vanilla registries. */
public final class AttributeTestRegistry implements ModInitializer {
    @Override public void onInitialize() {
        Registry.register(BuiltInRegistries.ATTRIBUTE, Identifier.parse("nbt-maker-test:extra_attribute"),
                new RangedAttribute("attribute.nbt-maker-test.extra_attribute", 0, -10000, 10000).setSyncable(true));
        var effect = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, Identifier.parse("nbt-maker-test:extra_effect"), (MobEffect)new ExtraEffect());
        Registry.register(BuiltInRegistries.POTION, Identifier.parse("nbt-maker-test:extra_potion"), new Potion("test_potion", new MobEffectInstance(effect,200,0)));
    }
    private static final class ExtraEffect extends MobEffect {
        ExtraEffect() { super(MobEffectCategory.BENEFICIAL,0x00AAFF); }
    }
}
