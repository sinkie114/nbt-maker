package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.component.Consumable;
import java.util.*;

final class FoodPotionEditorTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static EditSession session(Minecraft c){return ((EditorLayer)c.screen).session();}
    private static CompoundTag components(Minecraft c){return session(c).document.getCompoundOrEmpty("components");}
    static void exercise(ClientGameTestContext ctx,TestServerContext server){
        CompoundTag baseline=ctx.computeOnClient(c -> session(c).document.copy());
        click(ctx,"附魔");clickPage(ctx,"附魔（可继续新增附魔 ID）");
        search(ctx,"minecraft:sharpness","搜索附魔"); click(ctx,"删除");
        ctx.runOnClient(c -> {
            check(!components(c).getCompoundOrEmpty("minecraft:enchantments").contains("minecraft:sharpness"),"Delete updates enchantment data");
            check(has(c,"添加"),"Delete immediately turns the visible button into Add");
            check(field(c,"搜索附魔").getValue().equals("minecraft:sharpness"),"Deletion retains search");
        });
        click(ctx,"添加");
        search(ctx,"minecraft:unbreaking","搜索附魔");click(ctx,"添加");
        ctx.runOnClient(c -> check(components(c).getCompoundOrEmpty("minecraft:enchantments").contains("minecraft:sharpness"),"Consecutive additions cannot restore a stale snapshot"));
        click(ctx,"等级");input(ctx,"等级","7");click(ctx,"保存到副本");
        ctx.runOnClient(c -> {
            check(c.screen instanceof EnchantmentScreen,"Level editing returns to the same list");
            check(components(c).getCompoundOrEmpty("minecraft:enchantments").getIntOr("minecraft:unbreaking",0)==7,"Level change saved");
            check(hasContains(c,"等级 7"),"Level label updates without reopening");
        });
        ctx.takeScreenshot("nbt-enchantment-refresh");
        click(ctx,"返回"); click(ctx,"使用与行为");
        clickPage(ctx,"食物");
        CompoundTag beforeFood=ctx.computeOnClient(c -> session(c).document.copy());
        input(ctx,"恢复饥饿值","6"); input(ctx,"恢复饱和度","2.5");
        click(ctx,"取消");
        ctx.runOnClient(c -> check(session(c).document.equals(beforeFood),"Cancel adding food creates no component"));
        clickPage(ctx,"食物");input(ctx,"恢复饥饿值","bad");click(ctx,"保存到副本");
        ctx.runOnClient(c -> check(c.screen instanceof EditorFormScreen&&session(c).document.equals(beforeFood),"Invalid food input leaves draft unchanged"));
        input(ctx,"恢复饥饿值","6");input(ctx,"恢复饱和度","2.5");
        ctx.takeScreenshot("nbt-food-form");
        click(ctx,"保存到副本");
        ctx.runOnClient(c -> {
            check(session(c).error.isEmpty(),"Food and companion decode");
            check(components(c).getCompoundOrEmpty("minecraft:food").getIntOr("nutrition",0)==6,"Nutrition saved");
            check(components(c).contains("minecraft:consumable"),"Food form can add missing consume behavior");
        });
        clickPage(ctx,"可食用/饮用与使用时间");
        input(ctx,"使用时长（秒）","0.5");
        clickPage(ctx,"吃");search(ctx,"drink","搜索选项");clickContains(ctx,"drink");
        clickPageContains(ctx,"minecraft:entity.generic.eat");
        search(ctx,"minecraft:entity.generic.drink","搜索选项");clickContains(ctx,"minecraft:entity.generic.drink");
        clickPageContains(ctx,"编辑食用后效果");
        click(ctx,"新增食用后效果");clickContains(ctx,"minecraft:apply_effects");
        input(ctx,"触发概率（%）","100");
        clickPageContains(ctx,"编辑状态效果");click(ctx,"新增效果");
        search(ctx,"nbt-maker-test:extra_effect","搜索选项");clickContains(ctx,"nbt-maker-test:extra_effect");
        input(ctx,"等级","3");inputPage(ctx,"持续时间（秒）","12.35");
        ctx.runOnClient(c -> {c.options.guiScale().set(4);c.resizeDisplay();});
        ctx.waitTick();ctx.takeScreenshot("nbt-effect-form-small");
        ctx.runOnClient(c -> {c.options.guiScale().set(2);c.resizeDisplay();});
        click(ctx,"保存到副本");click(ctx,"返回");click(ctx,"保存到副本");
        // The nested list is still local to the outer consumable form.
        ctx.runOnClient(c -> check(components(c).getCompoundOrEmpty("minecraft:consumable").getListOrEmpty("on_consume_effects").isEmpty(),
                "Nested effect saves do not commit the outer form early"));
        // Exercise all built-in consume effect forms and registry based removals.
        click(ctx,"新增食用后效果");clickContains(ctx,"minecraft:remove_effects");
        clickPage(ctx,"选择要移除的效果");search(ctx,"minecraft:poison","搜索选项");clickContains(ctx,"minecraft:poison");
        click(ctx,"返回");click(ctx,"保存到副本");
        click(ctx,"新增食用后效果");clickContains(ctx,"minecraft:teleport_randomly");
        input(ctx,"传送范围直径（格）","8");click(ctx,"保存到副本");
        click(ctx,"新增食用后效果");clickContains(ctx,"minecraft:play_sound");click(ctx,"保存到副本");
        click(ctx,"新增食用后效果");clickContains(ctx,"minecraft:clear_all_effects");click(ctx,"保存到副本");
        ctx.takeScreenshot("nbt-consume-effects");
        click(ctx,"返回");click(ctx,"保存到副本");
        ctx.runOnClient(c -> {
            var consumable=components(c).getCompoundOrEmpty("minecraft:consumable");
            check(consumable.getFloatOr("consume_seconds",0)==0.5f&&consumable.getStringOr("animation","").equals("drink"),"Consume time and animation saved across selectors");
            ListTag actions=consumable.getListOrEmpty("on_consume_effects");
            check(actions.size()==5,"All five consume action forms create valid entries");
            var custom=(CompoundTag)((CompoundTag)actions.getFirst()).getListOrEmpty("effects").getFirst();
            check(custom.getIntOr("duration",0)==247&&custom.getIntOr("amplifier",0)==2,"User seconds and level map to ticks and amplifier");
            check(session(c).error.isEmpty(),"Consume actions decode: "+session(c).error);
        });
        clickPage(ctx,"使用冷却");input(ctx,"冷却时间（秒）","2.5");click(ctx,"保存到副本");
        clickPage(ctx,"使用后转换物品");input(ctx,"数量","2");click(ctx,"保存到副本");
        // Remove fields unused by real consumption test; preserve only apply-effects.
        ctx.runOnClient(c -> {
            var doc=session(c).document.copy();var comps=doc.getCompoundOrEmpty("components");
            var actions=comps.getCompoundOrEmpty("minecraft:consumable").getListOrEmpty("on_consume_effects");
            while(actions.size()>1)actions.remove(1);session(c).edit(doc);
        });
        clickPage(ctx,"药水与自定义效果");
        clickPage(ctx,"无基础药水");search(ctx,"nbt-maker-test:extra_potion","搜索选项");clickContains(ctx,"nbt-maker-test:extra_potion");
        clickPage(ctx,"查看基础效果");ctx.takeScreenshot("nbt-potion-base-effects");click(ctx,"返回");
        clickPageContains(ctx,"编辑自定义效果");click(ctx,"新增效果");
        search(ctx,"minecraft:speed","搜索选项");clickContains(ctx,"minecraft:speed");
        inputPage(ctx,"等级","2");inputPage(ctx,"持续时间（秒）","4.05");
        togglePage(ctx,"显示效果粒子");
        click(ctx,"保存到副本");
        click(ctx,"新增效果");search(ctx,"minecraft:strength","搜索选项");clickContains(ctx,"minecraft:strength");
        clickPage(ctx,"指定秒数");click(ctx,"保存到副本");
        ctx.takeScreenshot("nbt-potion-custom-effects");
        click(ctx,"返回"); inputPage(ctx,"自定义颜色（留空自动）","#12AB34");
        click(ctx,"保存到副本");
        clickPage(ctx,"药水持续时间倍率");input(ctx,"持续时间倍率","2");click(ctx,"保存到副本");
        ctx.runOnClient(c -> {
            var potion=components(c).getCompoundOrEmpty("minecraft:potion_contents");
            check(potion.getStringOr("potion","").equals("nbt-maker-test:extra_potion"),"Mod potion is selectable");
            check(potion.getIntOr("custom_color",0)==0x12AB34,"Potion color saved");
            var effects=potion.getListOrEmpty("custom_effects");
            check(((CompoundTag)effects.getFirst()).getIntOr("duration",0)==81,"Potion seconds preserve tick precision");
            var speed=(CompoundTag)effects.getFirst();
            check(!speed.getBooleanOr("show_particles",true)&&speed.getBooleanOr("show_icon",false),"Particle switch does not silently turn off the icon");
            check(((CompoundTag)effects.getLast()).getIntOr("duration",0)==-1,"Infinite effects supported");
            check(session(c).error.isEmpty(),"All new components decode: "+session(c).error);
        });
        // Preserve additional fields and nested hidden effects when editing known controls.
        ctx.runOnClient(c -> {
            var doc=session(c).document.copy();
            var food=doc.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:food");food.putString("extension_field","keep");
            session(c).edit(doc);
        });
        clickPage(ctx,"食物");input(ctx,"恢复饥饿值","7");click(ctx,"保存到副本");
        ctx.runOnClient(c -> check(components(c).getCompoundOrEmpty("minecraft:food").getStringOr("extension_field","").equals("keep"),"Forms preserve unedited extension fields"));
        // A later nested edit canceled at the component level must discard the whole transaction.
        CompoundTag beforeCancel=ctx.computeOnClient(c -> session(c).document.copy());
        clickPage(ctx,"药水与自定义效果");clickPageContains(ctx,"编辑自定义效果");click(ctx,"删除");click(ctx,"返回");click(ctx,"取消");
        ctx.runOnClient(c -> check(session(c).document.equals(beforeCancel),"Canceling outer potion form discards nested deletions"));
        click(ctx,"同步");ctx.waitFor(c -> session(c).status.equals("同步成功"));
        server.runOnServer(s -> {
            var player=s.getPlayerList().getPlayers().getFirst();var stack=player.getInventory().getItem(0);
            check(stack.get(DataComponents.FOOD).nutrition()==7,"Food UI reaches real server inventory");
            check(stack.get(DataComponents.USE_COOLDOWN).seconds()==2.5f,"Cooldown stored");
            check(stack.get(DataComponents.USE_REMAINDER).convertInto().getCount()==2,"Remainder stored");
            Consumable consumable=stack.get(DataComponents.CONSUMABLE);
            check(consumable.consumeSeconds()==0.5f,"Consumable stored");
            player.getFoodData().setFoodLevel(0);player.getFoodData().setSaturation(0);
            consumable.onConsume(player.level(),player,stack.copy());
            check(player.getFoodData().getFoodLevel()==7,"Generated food actually restores hunger");
            var effect=s.registryAccess().lookupOrThrow(Registries.MOB_EFFECT).getOrThrow(ResourceKey.create(Registries.MOB_EFFECT,Identifier.parse("nbt-maker-test:extra_effect")));
            check(player.hasEffect(effect),"Mod effect applies on consumption");
            check(player.hasEffect(MobEffects.SPEED),"Custom potion effect applies on consumption");
            player.removeAllEffects();
        });
        ctx.runOnClient(c -> session(c).edit(baseline));
        click(ctx,"同步");ctx.waitFor(c -> session(c).status.equals("同步成功"));
    }
    static void readOnly(ClientGameTestContext ctx) {
        CompoundTag baseline=ctx.computeOnClient(c -> session(c).document.copy());
        click(ctx,"使用与行为");
        for(String label:List.of("食物","可食用/饮用与使用时间","药水与自定义效果","使用冷却","使用后转换物品","药水持续时间倍率")) {
            clickPage(ctx,label);
            ctx.runOnClient(c -> {
                check(!session(c).editable,"Read-only source stays read-only");
                for(var widget:c.screen.children()) if(widget instanceof Button b && b.getMessage().getString().equals("保存到副本"))
                    check(!b.active,"Read-only form cannot save");
                for(var widget:c.screen.children()) if(widget instanceof EditBox box) check(!box.canConsumeInput(),"Read-only numeric inputs reject editing");
            });
            if(label.equals("可食用/饮用与使用时间") || label.equals("药水与自定义效果")) {
                clickPageContains(ctx,label.startsWith("可")?"编辑食用后效果":"编辑自定义效果");
                ctx.runOnClient(c -> { for(var widget:c.screen.children())if(widget instanceof Button b && (b.getMessage().getString().startsWith("新增")||b.getMessage().getString().equals("删除")))
                    check(!b.active,"Read-only nested lists cannot mutate"); });
                click(ctx,"返回");
            }
            click(ctx,"取消");
        }
        click(ctx,"附魔");clickPage(ctx,"附魔（可继续新增附魔 ID）");
        search(ctx,"minecraft:sharpness","搜索附魔");
        ctx.runOnClient(c -> {for(var widget:c.screen.children())if(widget instanceof Button b && (b.getMessage().getString().equals("添加")||b.getMessage().getString().equals("删除")))
            check(!b.active,"Read-only enchantment mutation controls disabled");});
        click(ctx,"返回");
        ctx.runOnClient(c -> check(session(c).document.equals(baseline),"Read-only exploration leaves the whole document untouched"));
    }
    private static EditBox field(Minecraft c,String label){
        return c.screen.children().stream().filter(w -> w instanceof EditBox e&&e.getMessage().getString().equals(label)).map(w -> (EditBox)w).findFirst().orElseThrow(() -> new AssertionError("Missing input: "+label));
    }
    private static boolean has(Minecraft c,String label){return c.screen.children().stream().anyMatch(w -> w instanceof Button b&&b.getMessage().getString().equals(label));}
    private static boolean hasContains(Minecraft c,String label){return c.screen.children().stream().anyMatch(w -> w instanceof Button b&&b.getMessage().getString().contains(label));}
    private static void search(ClientGameTestContext ctx,String text,String label){input(ctx,label,text);ctx.waitTick();}
    private static void input(ClientGameTestContext ctx,String label,String value){ctx.runOnClient(c -> field(c,label).setValue(value));}
    private static void inputPage(ClientGameTestContext ctx,String label,String value){
        for(int i=0;i<12;i++){
            if(ctx.computeOnClient(c -> c.screen.children().stream().anyMatch(w -> w instanceof EditBox e&&e.getMessage().getString().equals(label)))){input(ctx,label,value);return;}
            navigate(ctx);
        }throw new AssertionError("Input not found across pages: "+label);
    }
    private static void togglePage(ClientGameTestContext ctx,String label){
        for(int i=0;i<12;i++){
            boolean clicked=ctx.computeOnClient(c -> {
                // Each visible form field has a label painted above its widget. Find the toggle by its field order.
                var buttons=c.screen.children().stream().filter(w -> w instanceof Button b && (b.getMessage().getString().equals("开启")||b.getMessage().getString().equals("关闭")))
                        .map(w -> (Button)w).toList();
                if(label.equals("显示效果粒子") && buttons.size()==2){
                    var b=buttons.get(1);
                    c.screen.mouseClicked(new MouseButtonEvent(b.getX()+4,b.getY()+4,new MouseButtonInfo(0,0)),false);return true;
                }
                return false;
            });
            if(clicked){ctx.waitTick();return;}navigate(ctx);
        }throw new AssertionError("Missing particle toggle");
    }
    private static void clickPage(ClientGameTestContext ctx,String label){clickPage(ctx,label,false);}
    private static void clickPageContains(ClientGameTestContext ctx,String label){clickPage(ctx,label,true);}
    private static void clickPage(ClientGameTestContext ctx,String label,boolean contains){
        for(int i=0;i<12;i++){if(ctx.computeOnClient(c -> contains?hasContains(c,label):has(c,label))){click(ctx,label,contains);return;}navigate(ctx);}
        throw new AssertionError("Button not found across pages: "+label);
    }
    private static void navigate(ClientGameTestContext ctx){
        if(ctx.computeOnClient(c -> c.screen.children().stream().anyMatch(w -> w instanceof Button b&&b.active&&b.getMessage().getString().equals("下一页"))))click(ctx,"下一页");
        else while(ctx.computeOnClient(c -> c.screen.children().stream().anyMatch(w -> w instanceof Button b&&b.active&&b.getMessage().getString().equals("上一页"))))click(ctx,"上一页");
    }
    private static void click(ClientGameTestContext ctx,String label){click(ctx,label,false);}
    private static void clickContains(ClientGameTestContext ctx,String label){click(ctx,label,true);}
    private static void click(ClientGameTestContext ctx,String label,boolean contains){
        ctx.runOnClient(c -> {
            var b=c.screen.children().stream().filter(w -> w instanceof Button button&&(contains?button.getMessage().getString().contains(label):button.getMessage().getString().equals(label)))
                    .map(w -> (Button)w).findFirst().orElseThrow(() -> new AssertionError("Missing button: "+label));
            check(b.active,"Button enabled: "+label);
            c.screen.mouseClicked(new MouseButtonEvent(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,new MouseButtonInfo(0,0)),false);
        });ctx.waitTicks(2);
    }
}
