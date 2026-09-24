package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.consume_effects.RemoveStatusEffectsConsumeEffect;
import java.util.*;

/** A tagged holder set remains unchanged unless the user explicitly changes its selection. */
final class RemovalEffectsScreen extends Screen implements EditorLayer {
    private final EditorFormScreen parent;
    private final Set<String> selected=new LinkedHashSet<>();
    private final List<Button> rows=new ArrayList<>();
    private List<OptionPickerScreen.Option> options;
    private String query="";
    private int x,y,w,h,offset,pageSize;
    private Button previous,next;
    RemovalEffectsScreen(EditorFormScreen parent){
        super(Component.literal("选择要移除的效果"));this.parent=parent;
        var parsed=RemoveStatusEffectsConsumeEffect.CODEC.codec().parse(session().client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE),parent.draft).getOrThrow();
        parsed.effects().stream().forEach(h -> selected.add(h.unwrapKey().orElseThrow().identifier().toString()));
    }
    @Override public EditSession session(){return parent.session();}
    @Override protected void init(){
        w=Math.min(640,width-12);h=Math.min(410,height-12);x=(width-w)/2;y=(height-h)/2;pageSize=Math.max(1,(h-100)/24);
        options=RegistryOptions.effects(session());rows.clear();
        EditBox search=addRenderableWidget(new EditBox(font,x+8,y+28,w-16,20,Component.literal("搜索选项")));
        search.setMaxLength(256);search.setHint(Component.literal("搜索效果名称、ID 或 mod"));search.setValue(query);search.setResponder(v -> {query=v;offset=0;refresh();});
        previous=addRenderableWidget(Button.builder(Component.literal("上一页"),b -> {offset-=pageSize;refresh();}).bounds(x+8,y+h-28,62,20).build());
        next=addRenderableWidget(Button.builder(Component.literal("下一页"),b -> {offset+=pageSize;refresh();}).bounds(x+76,y+h-28,62,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),b -> onClose()).bounds(x+w-70,y+h-28,62,20).build());refresh();
    }
    private void refresh(){
        if(previous==null)return;rows.forEach(this::removeWidget);rows.clear();
        var list=options.stream().filter(o -> (o.id()+" "+o.name()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        for(int i=offset;i<Math.min(list.size(),offset+pageSize);i++){
            var option=list.get(i);
            Button b=addRenderableWidget(Button.builder(Component.literal((selected.contains(option.id())?"已选 · ":"选择 · ")+option.name()+" · "+option.id()),q -> {
                if(!parent.writable())return;if(!selected.remove(option.id()))selected.add(option.id());
                ListTag value=new ListTag();selected.forEach(id -> value.add(StringTag.valueOf(id)));parent.draft.put("effects",value);refresh();
            }).bounds(x+8,y+56+(i-offset)*24,w-16,20).build());b.active=parent.writable();rows.add(b);
        }
        previous.active=offset>0;next.active=offset+pageSize<list.size();
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(KeyEvent key){if(key.isEscape()){onClose();return true;}return super.keyPressed(key);}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        ItemEditorScreen.panel(g,x,y,w,h);g.drawString(font,title,x+8,y+9,0xFF303030,false);
        g.drawString(font,"已选 "+selected.size()+" 项；返回后保存",x+8,y+h-44,0xFF555555,false);super.render(g,mx,my,delta);
    }
}
