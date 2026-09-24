package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import java.math.BigDecimal;
import java.util.*;

/** Nested lists write to their containing form, which commits atomically only on Save. */
final class EffectListScreen extends Screen implements EditorLayer {
    private final EditorFormScreen parent;
    private final String key;
    private final boolean consume;
    private List<OptionPickerScreen.Option> effects;
    private int x,y,w,h,offset,pageSize;
    private String error="";
    private static final Map<String,String> ACTIONS=new LinkedHashMap<>();
    static {
        ACTIONS.put("minecraft:apply_effects","给予状态效果");
        ACTIONS.put("minecraft:remove_effects","移除指定效果");
        ACTIONS.put("minecraft:clear_all_effects","清除全部效果");
        ACTIONS.put("minecraft:teleport_randomly","随机传送");
        ACTIONS.put("minecraft:play_sound","播放声音");
    }
    EffectListScreen(EditorFormScreen parent,String key,boolean consume) {
        super(Component.literal(consume?"食用后效果":"自定义状态效果"));this.parent=parent;this.key=key;this.consume=consume;
    }
    @Override public EditSession session(){return parent.session();}
    private boolean writable(){return parent.writable();}
    private ListTag entries(){
        Tag value=parent.draft.get(key);if(value==null)return new ListTag();
        if(value instanceof ListTag list)return list;
        throw new IllegalArgumentException("效果列表格式错误；请在高级模式检查。");
    }
    @Override protected void init(){
        w=Math.min(680,width-12);h=Math.min(420,height-12);x=(width-w)/2;y=(height-h)/2;
        pageSize=Math.max(1,(h-98)/38);effects=RegistryOptions.effects(session());
        ListTag list;try{list=entries();}catch(RuntimeException ex){error=ex.getMessage();list=new ListTag();}
        offset=Math.max(0,Math.min(offset,Math.max(0,(list.size()-1)/pageSize*pageSize)));
        for(int i=offset;i<Math.min(list.size(),offset+pageSize);i++){
            final int index=i;Tag raw=list.get(i);int by=y+32+(i-offset)*38;
            String name=raw instanceof CompoundTag c?entryName(c):"格式错误的效果";
            boolean supported=raw instanceof CompoundTag c && (!consume || ACTIONS.containsKey(type(c)));
            Button nameButton=button(name,x+8,by,w-150,() -> edit(index,(CompoundTag)raw));nameButton.active=supported;
            nameButton.setTooltip(Tooltip.create(Component.literal(raw instanceof CompoundTag c?(consume?type(c):c.getStringOr("id","")):"")));
            button(session().editable?"编辑":"查看",x+w-138,by,62,() -> edit(index,(CompoundTag)raw)).active=supported;
            button("删除",x+w-70,by,62,() -> {if(writable()){ListTag next=entries().copy();next.remove(index);parent.draft.put(key,next);rebuildWidgets();}}).active=writable();
        }
        button(consume?"新增食用后效果":"新增效果",x+8,y+h-28,110,this::add).active=writable()&&error.isEmpty();
        button("上一页",x+124,y+h-28,58,() -> {offset-=pageSize;rebuildWidgets();}).active=offset>0;
        button("下一页",x+186,y+h-28,58,() -> {offset+=pageSize;rebuildWidgets();}).active=offset+pageSize<list.size();
        button("返回",x+w-70,y+h-28,62,this::onClose);
    }
    private Button button(String name,int bx,int by,int bw,Runnable task){
        return addRenderableWidget(Button.builder(Component.literal(name),b -> task.run()).bounds(bx,by,bw,20).build());
    }
    private String type(CompoundTag data){String id=data.getStringOr("type","");return id.contains(":")?id:"minecraft:"+id;}
    private String entryName(CompoundTag data){return consume?ACTIONS.getOrDefault(type(data),type(data)):RegistryOptions.name(effects,data.getStringOr("id","?"));}
    private String summary(CompoundTag data){
        if(!consume)return "等级 "+((long)data.getIntOr("amplifier",0)+1)+" · "+(data.getIntOr("duration",0)==-1?"无限":data.getIntOr("duration",0)/20.0+" 秒");
        return switch(type(data)){
            case "minecraft:apply_effects" -> ComponentForms.listSize(data,"effects")+" 项效果 · 概率 "+data.getFloatOr("probability",1)*100+"%";
            case "minecraft:teleport_randomly" -> "传送范围直径 "+data.getFloatOr("diameter",16);
            case "minecraft:clear_all_effects" -> "食用后清除身上的全部状态效果";
            case "minecraft:play_sound" -> data.getStringOr("sound","自定义声音");
            case "minecraft:remove_effects" -> "食用后移除选中的状态效果";
            default -> "扩展动作：可保留或删除，详细数据在高级模式编辑";
        };
    }
    private void add(){
        if(!writable())return;
        List<OptionPickerScreen.Option> options=consume?ACTIONS.entrySet().stream().map(e -> new OptionPickerScreen.Option(e.getKey(),e.getValue())).toList():effects;
        minecraft.setScreen(new OptionPickerScreen(this,consume?"选择食用后效果":"选择状态效果",options,id -> {
            CompoundTag data=new CompoundTag();
            if(consume){
                data.putString("type",id);
                switch(id){
                    case "minecraft:apply_effects" -> {data.put("effects",new ListTag());data.putFloat("probability",1);}
                    case "minecraft:remove_effects" -> data.put("effects",new ListTag());
                    case "minecraft:teleport_randomly" -> data.putFloat("diameter",16);
                    case "minecraft:play_sound" -> data.putString("sound","minecraft:entity.generic.eat");
                }
            }else{data.putString("id",id);data.putInt("duration",600);data.putInt("amplifier",0);}
            edit(-1,data);
        }));
    }
    private void commit(int index,CompoundTag original,CompoundTag value){
        if(!writable())return;
        var ops=session().client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        if(consume)ConsumeEffect.CODEC.parse(ops,value).getOrThrow();else MobEffectInstance.CODEC.parse(ops,value).getOrThrow();
        ListTag next=entries().copy();
        if(index<0)next.add(value);
        else{if(index>=next.size()||!next.get(index).equals(original))throw new IllegalArgumentException("该效果已经变化，请返回重试。");next.set(index,value);}
        parent.draft.put(key,next);
    }
    private void edit(int index,CompoundTag original){
        if(!consume){statusForm(index,original);return;}
        var form=new EditorFormScreen(this,index<0?"新增食用后效果":"编辑食用后效果",original,result -> commit(index,original,result));
        switch(type(original)){
            case "minecraft:apply_effects" -> {
                form.input("触发概率（%）","0 至 100",Double.toString(original.getFloatOr("probability",1)*100.0),(result,text) ->
                        result.putFloat("probability",(float)(EditorFormScreen.numeric(text,"概率",0,100)/100)));
                form.action("给予的状态效果",() -> "编辑状态效果（"+ComponentForms.listSize(form.draft,"effects")+" 项）",
                        () -> minecraft.setScreen(new EffectListScreen(form,"effects",false)),true);
            }
            case "minecraft:remove_effects" -> form.action("要移除的效果",() -> "选择要移除的效果",() -> minecraft.setScreen(new RemovalEffectsScreen(form)),true);
            case "minecraft:teleport_randomly" -> form.number("diameter","传送范围直径（格）",16,false,Float.MIN_VALUE,Float.MAX_VALUE);
            case "minecraft:play_sound" -> ComponentForms.soundField(form,"sound","声音","minecraft:entity.generic.eat");
            case "minecraft:clear_all_effects" -> form.action("行为",() -> "食用后清除所有状态效果",() -> {},true);
        }
        minecraft.setScreen(form);
    }
    private void statusForm(int index,CompoundTag original){
        boolean[] infinite={original.getIntOr("duration",0)==-1};
        var form=new EditorFormScreen(this,index<0?"新增状态效果":"编辑状态效果",original,result -> {
            if(infinite[0])result.putInt("duration",-1);
            else if(result.getIntOr("duration",0)==-1)result.putInt("duration",600);
            if(!original.contains("show_icon") && !result.contains("show_icon")
                    && result.getBooleanOr("show_particles",true)!=original.getBooleanOr("show_particles",true))
                result.putBoolean("show_icon",original.getBooleanOr("show_particles",true));
            commit(index,original,result);
        });
        form.choice("状态效果",() -> RegistryOptions.name(effects,form.draft.getStringOr("id","")),() -> effects,v -> form.draft.putString("id",v));
        form.input("等级","1 表示 I 级；无需填写内部放大值。",Long.toString((long)original.getIntOr("amplifier",0)+1),(result,text) -> {
            double value=EditorFormScreen.numeric(text,"等级",1,256);
            if(value!=Math.rint(value))throw new IllegalArgumentException("等级需要整数。");result.putInt("amplifier",(int)value-1);
        });
        String duration=original.getIntOr("duration",0)==-1?"30":BigDecimal.valueOf(original.getIntOr("duration",0)).divide(BigDecimal.valueOf(20)).stripTrailingZeros().toPlainString();
        form.input("持续时间（秒）","最小精度 0.05 秒；瞬间效果无需持续时间。",duration,(result,text) -> {
            try{
                int ticks=new BigDecimal(text.trim()).multiply(BigDecimal.valueOf(20)).intValueExact();
                if(ticks<0)throw new IllegalArgumentException();result.putInt("duration",ticks);
            }catch(RuntimeException ex){throw new IllegalArgumentException("持续时间请输入非负秒数，精度 0.05 秒，且不能超过游戏整数范围。");}
        });
        form.action("持续时间模式",() -> infinite[0]?"无限时间":"指定秒数",() -> {infinite[0]=!infinite[0];form.refreshFields();},false);
        form.toggle("ambient","环境效果（淡化粒子）",false).toggle("show_particles","显示效果粒子",true)
                .toggle("show_icon","显示效果图标",original.getBooleanOr("show_particles",true));
        minecraft.setScreen(form);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(KeyEvent key){if(key.isEscape()){onClose();return true;}return super.keyPressed(key);}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        ItemEditorScreen.panel(g,x,y,w,h);g.drawString(font,title,x+8,y+9,0xFF303030,false);
        try{
            var list=entries(); if(list.isEmpty())g.drawString(font,"暂无效果，点击“新增”选择。",x+8,y+34,0xFF555555,false);
            for(int i=offset;i<Math.min(list.size(),offset+pageSize);i++)if(list.get(i) instanceof CompoundTag c)
                g.drawString(font,font.plainSubstrByWidth(summary(c),w-16),x+8,y+55+(i-offset)*38,0xFF555555,false);
        }catch(RuntimeException ignored){}
        g.drawString(font,font.plainSubstrByWidth(error.isEmpty()?"返回后保存所在组件，才会载入物品副本。":error,w-16),x+8,y+h-44,0xFF555555,false);
        super.render(g,mx,my,delta);
    }
}
