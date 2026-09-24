package com.sinkie114.client;

import java.util.*;

public enum EditorPage {
    STACK("ItemStack", "物品 ID、数量；双击节点编辑。", ""),
    DISPLAY("显示信息", "名称、Lore、颜色、模型、提示信息", "custom_name,item_name,lore,rarity,custom_model_data,item_model,dyed_color,tooltip_display,enchantment_glint_override,map_color,map_id,profile,banner_patterns,base_color,trim"),
    ENCHANTMENTS("附魔", "普通附魔和存储附魔；等级不限于原版上限", "enchantments,stored_enchantments,enchantable"),
    ATTRIBUTES("属性", "属性值、运算类型、修饰符 ID、生效部位", "attribute_modifiers,damage,max_damage,max_stack_size,unbreakable,repair_cost,repairable,equippable"),
    BEHAVIOR("使用与行为", "食物、药水、冷却、使用时长、工具和声音", "food,consumable,use_cooldown,use_remainder,potion_contents,potion_duration_scale,weapon,tool,blocks_attacks,damage_resistant,death_protection,kinetic_weapon,piercing_weapon,swing_animation,use_effects,can_break,can_place_on,jukebox_playable,instrument,break_sound,recipes,lodestone_tracker"),
    CONTENTS("容器与物品", "选中带 id 的 Compound，点击“内部物品”继续编辑", "container,bundle_contents,charged_projectiles,container_loot,fireworks,firework_explosion,block_entity_data,bucket_entity_data,entity_data,bees,writable_book_content,written_book_content"),
    CUSTOM("自定义数据", "minecraft:custom_data；支持任意 NBT 类型和多层嵌套", "custom_data"),
    RAW("原始数据", "完整数据树，包含默认组件、移除标记和第三方组件", "");

    public final String title, hint;
    public final Set<String> components;
    EditorPage(String title, String hint, String keys) {
        this.title = title; this.hint = hint;
        components = new LinkedHashSet<>();
        if (!keys.isEmpty()) for (String key : keys.split(",")) components.add("minecraft:" + key);
    }
    public boolean accepts(String key) { return components.contains(key.startsWith("!") ? key.substring(1) : key); }
}
