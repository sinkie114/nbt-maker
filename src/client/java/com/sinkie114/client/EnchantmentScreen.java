package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.*;

final class EnchantmentScreen extends Screen implements EditorLayer {
    private final SimpleComponentScreen parent;
    private final String key;
    private final List<Button> rows = new ArrayList<>();
    private List<OptionPickerScreen.Option> options;
    private String query = "";
    private int x,y,w,h,offset,pageSize,revision,matches;
    private Button previous,next;
    EnchantmentScreen(SimpleComponentScreen parent, String key) { super(Component.literal("附魔")); this.parent=parent; this.key=key; }
    @Override public EditSession session() { return parent.session(); }
    private boolean writable() { return session().editable && !session().syncing; }
    private CompoundTag data() { return parent.components().getCompoundOrEmpty(key); }
    @Override protected void init() {
        w=Math.min(680,width-12); h=Math.min(440,height-12); x=(width-w)/2; y=(height-h)/2;
        pageSize=Math.max(1,(h-100)/25); rows.clear(); options=RegistryOptions.enchantments(session());
        EditBox search=addRenderableWidget(new EditBox(font,x+8,y+28,w-16,20,Component.literal("搜索附魔")));
        search.setMaxLength(256); search.setHint(Component.literal("搜索名称、ID 或 mod")); search.setValue(query);
        search.setResponder(s -> { query=s; offset=0; refresh(); });
        previous=addRenderableWidget(Button.builder(Component.literal("上一页"),b -> { offset-=pageSize; refresh(); }).bounds(x+8,y+h-28,62,20).build());
        next=addRenderableWidget(Button.builder(Component.literal("下一页"),b -> { offset+=pageSize; refresh(); }).bounds(x+76,y+h-28,62,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),b -> onClose()).bounds(x+w-70,y+h-28,62,20).build());
        refresh(); setInitialFocus(search);
    }
    private void refresh() {
        if(previous==null)return;
        rows.forEach(this::removeWidget); rows.clear(); revision=session().revision;
        String needle=query.toLowerCase(Locale.ROOT);
        var filtered=options.stream().filter(o -> (o.id()+" "+o.name()).toLowerCase(Locale.ROOT).contains(needle)).toList();
        matches=filtered.size(); offset=Math.max(0,Math.min(offset,Math.max(0,(matches-1)/pageSize*pageSize)));
        for(int i=offset;i<Math.min(matches,offset+pageSize);i++) {
            var option=filtered.get(i); String id=option.id(); boolean present=data().contains(id); int ry=y+56+(i-offset)*25;
            addRow(option.name()+" · "+(present?"等级 "+data().getIntOr(id,1):"未添加"),x+8,ry,w-156,() -> level(id),true)
                    .setTooltip(Tooltip.create(Component.literal(id)));
            addRow(present?"删除":"添加",x+w-142,ry,62,() -> {
                if(!writable())return;
                CompoundTag current=data().copy();
                if(current.contains(id))current.remove(id);else current.putInt(id,1);
                parent.setComponent(key,current); refresh();
            },writable());
            addRow("等级",x+w-74,ry,66,() -> level(id),present);
        }
        previous.active=offset>0; next.active=offset+pageSize<matches;
    }
    private Button addRow(String text,int bx,int by,int bw,Runnable action,boolean active) {
        Button b=addRenderableWidget(Button.builder(Component.literal(text),q -> action.run()).bounds(bx,by,bw,20).build());
        b.active=active; rows.add(b); return b;
    }
    private void level(String id) {
        CompoundTag current=new CompoundTag(); current.putInt("level",data().getIntOr(id,1));
        var form=new EditorFormScreen(this,"设置附魔等级",current,result -> {
            CompoundTag all=data().copy(); all.putInt(id,result.getIntOr("level",1));
            RuntimeEnchantments.read(all,session().client.level.registryAccess()); parent.setComponent(key,all);
        });
        form.input("等级","整数范围：-2147483648 至 2147483647",Integer.toString(current.getIntOr("level",1)),
                (result,text) -> result.putInt("level",RuntimeEnchantments.parseLevel(text)));
        minecraft.setScreen(form);
    }
    @Override public void tick() { if(revision!=session().revision)refresh(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) { if(key.isEscape()){onClose();return true;} return super.keyPressed(key); }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        ItemEditorScreen.panel(g,x,y,w,h); g.drawString(font,"附魔 · "+(key.endsWith("stored_enchantments")?"存储附魔":"普通附魔"),x+8,y+9,0xFF303030,false);
        g.drawString(font,"共 "+matches+" 项",x+8,y+h-44,0xFF555555,false); super.render(g,mx,my,delta);
    }
}
