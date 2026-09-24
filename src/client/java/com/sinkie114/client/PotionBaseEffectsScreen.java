package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import java.util.*;

/** Preset effects are inherited from the selected potion, not editable custom entries. */
final class PotionBaseEffectsScreen extends Screen implements EditorLayer {
    private final EditorFormScreen parent;
    private List<String> lines=List.of();
    private int x,y,w,h,offset,pageSize;
    PotionBaseEffectsScreen(EditorFormScreen parent) { super(Component.literal("基础药水效果"));this.parent=parent; }
    @Override public EditSession session(){return parent.session();}
    @Override protected void init(){
        w=Math.min(560,width-12);h=Math.min(350,height-12);x=(width-w)/2;y=(height-h)/2;pageSize=Math.max(1,(h-75)/22);
        String id=parent.draft.getStringOr("potion","");
        try {
            lines=session().client.level.registryAccess().lookupOrThrow(Registries.POTION).getOrThrow(ResourceKey.create(Registries.POTION,Identifier.parse(id))).value().getEffects()
                    .stream().map(e -> e.getEffect().value().getDisplayName().getString()+" · 等级 "+(e.getAmplifier()+1)+" · "+(e.isInfiniteDuration()?"无限":e.getDuration()/20.0+" 秒")).toList();
        }catch(RuntimeException ex){lines=List.of();}
        addRenderableWidget(Button.builder(Component.literal("上一页"),b -> {offset-=pageSize;rebuildWidgets();}).bounds(x+8,y+h-28,62,20).build()).active=offset>0;
        addRenderableWidget(Button.builder(Component.literal("下一页"),b -> {offset+=pageSize;rebuildWidgets();}).bounds(x+76,y+h-28,62,20).build()).active=offset+pageSize<lines.size();
        addRenderableWidget(Button.builder(Component.literal("返回"),b -> onClose()).bounds(x+w-70,y+h-28,62,20).build());
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(KeyEvent key){if(key.isEscape()){onClose();return true;}return super.keyPressed(key);}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        ItemEditorScreen.panel(g,x,y,w,h);g.drawString(font,title,x+8,y+8,0xFF303030,false);
        if(lines.isEmpty())g.drawString(font,"当前基础药水没有效果",x+8,y+32,0xFF555555,false);
        for(int i=offset;i<Math.min(lines.size(),offset+pageSize);i++)g.drawString(font,font.plainSubstrByWidth(lines.get(i),w-16),x+8,y+32+(i-offset)*22,0xFF303030,false);
        g.drawString(font,font.plainSubstrByWidth("这些效果来自基础药水；选择“无基础药水”可全部移除。",w-16),x+8,y+h-44,0xFF555555,false);
        super.render(g,mx,my,delta);
    }
}
