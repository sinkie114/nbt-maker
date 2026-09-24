package com.sinkie114.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Component-specific fields; the source tag is copied and only edited fields are replaced. */
final class ComponentForms {
    static final Set<String> BEHAVIOR_KEYS=Set.of("minecraft:food","minecraft:consumable","minecraft:use_cooldown",
            "minecraft:use_remainder","minecraft:potion_contents","minecraft:potion_duration_scale");
    /** 所有有专用表单的组件（行为类 + 显示信息类 + 容器类）。 */
    static final Set<String> KEYS=union(BEHAVIOR_KEYS,DisplayForms.KEYS,ItemGridScreen.KEYS);
    @SafeVarargs private static Set<String> union(Set<String>... sets){var all=new HashSet<String>();for(var set:sets)all.addAll(set);return Set.copyOf(all);}
    private ComponentForms() {}
    static void validate(EditSession session,String key,Tag value) {
        var type=BuiltInRegistries.DATA_COMPONENT_TYPE.get(Identifier.parse(key)).orElseThrow().value();
        EditorComponentData.read(type,value,session.client.level.registryAccess());
    }
    static CompoundTag defaults(String key) {
        if(key.equals("minecraft:potion_contents"))return new CompoundTag();
        String value=TemplateScreen.ALL.stream().filter(t -> ("minecraft:"+t.key()).equals(key)).findFirst().orElseThrow().value();
        try{return TagParser.parseCompoundFully(value);}catch(Exception ex){throw new IllegalArgumentException(ex);}
    }
    static void open(SimpleComponentScreen parent,String key) {
        try {
            if(DisplayForms.KEYS.contains(key)){DisplayForms.open(parent,key);return;}
            if(ItemGridScreen.KEYS.contains(key)){ItemGridScreen.open(parent,key);return;}
            var client=parent.session().client;
            Tag existing=parent.components().get(key);
            if(key.equals("minecraft:potion_duration_scale")) {
                CompoundTag value=new CompoundTag(); value.put("value",existing==null?FloatTag.valueOf(1):existing.copy());
                var form=new EditorFormScreen(parent,"药水持续时间倍率",value,data -> save(parent,key,data.get("value")));
                form.number("value","持续时间倍率",1,false,0,Float.MAX_VALUE); client.setScreen(form); return;
            }
            CompoundTag data;
            if(existing instanceof CompoundTag compound)data=compound.copy();
            else if(existing instanceof StringTag string && key.equals("minecraft:potion_contents")) { data=new CompoundTag();data.putString("potion",string.value()); }
            else if(existing==null)data=defaults(key);
            else throw new IllegalArgumentException("组件格式不正确，请先在高级模式检查。");
            EditorFormScreen form;
            switch(key) {
                case "minecraft:food" -> {
                    boolean[] companion={parent.components().get("minecraft:consumable")==null};
                    form=new EditorFormScreen(parent,"食物",data,result -> {
                        Map<String,Tag> edits=new LinkedHashMap<>(); edits.put(key,result);
                        if(companion[0])edits.put("minecraft:consumable",defaults("minecraft:consumable"));
                        edits.forEach((id,value) -> validate(parent.session(),id,value)); parent.setComponents(edits);
                    });
                    form.number("nutrition","恢复饥饿值",1,true,0,Integer.MAX_VALUE)
                            .number("saturation","恢复饱和度",0,false,0,Float.MAX_VALUE)
                            .toggle("can_always_eat","饱食时也可吃",false);
                    if(companion[0])form.action("同时添加食用行为",() -> companion[0]?"开启（让物品可以吃）":"关闭",() -> {
                        companion[0]=!companion[0]; form.refreshFields();
                    },false);
                }
                case "minecraft:consumable" -> {
                    form=new EditorFormScreen(parent,"食用与饮用",data,result -> save(parent,key,result));
                    form.number("consume_seconds","使用时长（秒）",1.6,false,0,Float.MAX_VALUE);
                    var animations=RegistryOptions.animations();
                    form.choice("使用动作",() -> RegistryOptions.name(animations,form.draft.getStringOr("animation","eat")),() -> animations,v -> form.draft.putString("animation",v));
                    soundField(form,"sound","使用声音","minecraft:entity.generic.eat");
                    form.toggle("has_consume_particles","显示食用粒子",true);
                    form.action("食用后效果",() -> "编辑食用后效果（"+listSize(form.draft,"on_consume_effects")+" 项）",
                            () -> client.setScreen(new EffectListScreen(form,"on_consume_effects",true)),true);
                }
                case "minecraft:use_cooldown" -> {
                    form=new EditorFormScreen(parent,"使用冷却",data,result -> save(parent,key,result));
                    form.number("seconds","冷却时间（秒）",1,false,0,Float.MAX_VALUE);
                    form.input("共享冷却组（留空使用物品默认）","相同组的物品共用冷却；也可保留默认。",data.getStringOr("cooldown_group",""),(result,text) -> {
                        if(text.isBlank())result.remove("cooldown_group");
                        else { var id=Identifier.tryParse(text.trim()); if(id==null)throw new IllegalArgumentException("冷却组 ID 格式错误。"); result.putString("cooldown_group",id.toString()); }
                    });
                }
                case "minecraft:use_remainder" -> {
                    form=new EditorFormScreen(parent,"使用后返还物品",data,result -> save(parent,key,result));
                    var items=RegistryOptions.items();
                    form.choice("返还物品",() -> RegistryOptions.name(items,form.draft.getStringOr("id","")),() -> items,v -> form.draft.putString("id",v));
                    form.number("count","数量",1,true,1,Integer.MAX_VALUE);
                }
                case "minecraft:potion_contents" -> {
                    form=new EditorFormScreen(parent,"药水与自定义效果",data,result -> save(parent,key,result));
                    var potions=RegistryOptions.withNone(RegistryOptions.potions(parent.session()),"无基础药水");
                    form.choice("基础药水",() -> RegistryOptions.name(potions,form.draft.getStringOr("potion","")),() -> potions,v -> {
                        if(v.isEmpty())form.draft.remove("potion");else form.draft.putString("potion",v);
                    });
                    form.action("基础药水附带的效果",() -> "查看基础效果",() -> client.setScreen(new PotionBaseEffectsScreen(form)),true);
                    form.action("自定义效果",() -> "编辑自定义效果（"+listSize(form.draft,"custom_effects")+" 项）",
                            () -> client.setScreen(new EffectListScreen(form,"custom_effects",false)),true);
                    form.input("自定义颜色（留空自动）","十六进制颜色，例如 #FF0000 表示红色。",data.contains("custom_color")?String.format("#%06X",data.getIntOr("custom_color",0)):"",(result,text) -> {
                        if(text.isBlank()){result.remove("custom_color");return;}
                        String hex=text.trim(); if(hex.startsWith("#"))hex=hex.substring(1);
                        if(!hex.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("颜色请输入六位十六进制，例如 #FF0000。");
                        result.putInt("custom_color",Integer.parseInt(hex,16));
                    });
                    form.input("药水名称后缀（留空默认）","可保留默认名称。",data.getStringOr("custom_name",""),(result,text) -> {
                        if(text.isBlank())result.remove("custom_name");else result.putString("custom_name",text);
                    });
                }
                default -> throw new IllegalArgumentException("没有对应表单");
            }
            client.setScreen(form);
        } catch(RuntimeException ex) { parent.session().status=ex.getMessage(); }
    }
    static int listSize(CompoundTag tag,String key) { return tag.get(key) instanceof ListTag list?list.size():0; }
    static void soundField(EditorFormScreen form,String key,String label,String fallback) {
        var sounds=RegistryOptions.sounds();
        form.choice(label,() -> {
            Tag value=form.draft.get(key);
            return value instanceof StringTag s?s.value():value instanceof CompoundTag c?c.getStringOr("sound_id",fallback):fallback;
        },() -> sounds,v -> form.draft.putString(key,v));
    }
    private static void save(SimpleComponentScreen parent,String key,Tag value) {
        validate(parent.session(),key,value); parent.setComponent(key,value);
    }
}
