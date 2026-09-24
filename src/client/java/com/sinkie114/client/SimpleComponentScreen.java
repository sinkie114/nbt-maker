package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Form-based editor for common components. Advanced tree editing is always available. */
public final class SimpleComponentScreen extends Screen implements EditorLayer {
    private final EditSession session;
    private final ItemEditorScreen advancedParent;
    /** 内部物品模式：编辑文档里 path 处的物品，关闭时回到 back（通常是格子界面）。 */
    private final List<Object> nestedPath;
    private final Screen back;
    private EditorPage category = EditorPage.DISPLAY;
    private int x, y, w, h, componentOffset, pageSize, revision;

    public SimpleComponentScreen(EditSession session, ItemEditorScreen advancedParent) {
        super(Component.literal("物品组件（简单模式）"));
        this.session = session; this.advancedParent = advancedParent; this.nestedPath = List.of(); this.back = null;
    }
    public SimpleComponentScreen(EditSession session, List<Object> path, Screen back) {
        super(Component.literal("内部物品（简单模式）"));
        this.session = session; this.advancedParent = null; this.nestedPath = List.copyOf(path); this.back = back;
    }
    /** 当前编辑的物品在整个文档中的路径；顶层物品为空列表。 */
    List<Object> path() { return advancedParent != null ? advancedParent.rootPath() : nestedPath; }
    private boolean nested() { return !path().isEmpty(); }
    @Override public EditSession session() { return session; }

    private CompoundTag root() {
        if (advancedParent != null) return advancedParent.currentRoot();
        return StackData.at(session.document, nestedPath) instanceof CompoundTag c ? c : new CompoundTag();
    }
    CompoundTag components() { return root().getCompoundOrEmpty("components"); }
    private boolean present(String key) { return components().contains(key); }
    void setComponent(String key, Tag value) {
        setComponents(Map.of(key,value));
    }
    void setComponents(Map<String,Tag> edits) {
        if (!session.editable || session.syncing) return;
        CompoundTag copy = root().copy(), comps = copy.getCompoundOrEmpty("components").copy();
        edits.forEach((key,value) -> { comps.remove("!" + key); comps.put(key,value.copy()); });
        copy.put("components", comps); setRoot(copy);
    }
    private void setRoot(CompoundTag root) {
        session.edit(StackData.replace(session.document, path(), root));
    }
    private void removeComponent(String key) {
        if (!session.editable || session.syncing) return;
        CompoundTag copy = root().copy(), comps = copy.getCompoundOrEmpty("components").copy();
        comps.remove(key); comps.put("!" + key, new CompoundTag()); copy.put("components", comps); setRoot(copy); rebuildWidgets();
    }

    @Override protected void init() {
        revision = session.revision;
        w = Math.min(760, width - 12); h = Math.min(470, height - 12); x = (width - w) / 2; y = (height - h) / 2;
        addRenderableWidget(Button.builder(Component.literal("物品基础"), b -> openBasic()).bounds(x + 8, y + 28, 86, 20).build());
        EditorPage[] pages = {EditorPage.DISPLAY, EditorPage.ENCHANTMENTS, EditorPage.ATTRIBUTES, EditorPage.BEHAVIOR, EditorPage.CONTENTS, EditorPage.CUSTOM};
        int tw = (w - 102) / pages.length;
        for (int i = 0; i < pages.length; i++) {
            EditorPage p = pages[i]; Button b = addRenderableWidget(Button.builder(Component.literal(p.title), q -> { category = p; componentOffset = 0; rebuildWidgets(); })
                    .bounds(x + 98 + i * tw, y + 28, tw - 2, 20).build()); b.active = p != category;
        }
        List<TemplateScreen.Template> list = TemplateScreen.ALL.stream().filter(t -> category.accepts("minecraft:" + t.key()))
                .sorted(Comparator.comparing(TemplateScreen.Template::name)).toList();
        int top = y + 58; int row = 27; pageSize = Math.max(1, (h - 142) / row);
        componentOffset = Math.max(0, Math.min(componentOffset, Math.max(0, (list.size()-1) / pageSize * pageSize)));
        for (int i = componentOffset; i < list.size() && i < componentOffset + pageSize; i++) {
            TemplateScreen.Template t = list.get(i); String key = "minecraft:" + t.key(); boolean has = present(key); int ry = top + (i-componentOffset) * row;
            boolean attributes = key.equals(AttributeModifierScreen.KEY);
            boolean visual = attributes || ComponentForms.KEYS.contains(key) || key.endsWith("enchantments");
            addRenderableWidget(Button.builder(Component.literal(t.name()), b -> edit(key, t))
                    .bounds(x + 8, ry, w - 250, 21).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(key))).build()).active = visual || session.editable;
            Button add = addRenderableWidget(Button.builder(Component.literal(has ? "已添加" : "添加"), b -> {
                if (attributes) minecraft.setScreen(new AttributeModifierScreen(this));
                else if (ComponentForms.KEYS.contains(key)) ComponentForms.open(this,key);
                else if (key.endsWith("enchantments")) minecraft.setScreen(new EnchantmentScreen(this,key));
                else if (!has) { setComponent(key, parse(t.value())); rebuildWidgets(); }
            })
                    .bounds(x + w - 242, ry, 62, 21).build()); add.active = session.editable && !session.syncing && !has;
            Button edit = addRenderableWidget(Button.builder(Component.literal(session.editable?"编辑":"查看"), b -> edit(key, t)).bounds(x + w - 176, ry, 62, 21).build());
            edit.active = visual || session.editable && !session.syncing && has && supported(key);
            Button del = addRenderableWidget(Button.builder(Component.literal("删除"), b -> removeComponent(key)).bounds(x + w - 110, ry, 62, 21).build());
            del.active = session.editable && !session.syncing && has;
        }
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { componentOffset -= pageSize; rebuildWidgets(); }).bounds(x+8,y+h-70,62,20).build()).active = componentOffset > 0;
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { componentOffset += pageSize; rebuildWidgets(); }).bounds(x+76,y+h-70,62,20).build()).active = componentOffset + pageSize < list.size();
        addRenderableWidget(Button.builder(Component.literal("高级模式"), b -> minecraft.setScreen(advancedParent == null ? new ItemEditorScreen(session, null, nestedPath) : advancedParent))
                .bounds(x + 8, y + h - 28, 92, 20).build());
        if (session.editable) addRenderableWidget(Button.builder(Component.literal("同步"), b -> synchronize()).bounds(x + w - 208, y + h - 28, 62, 20).build());
        addRenderableWidget(Button.builder(Component.literal(back != null ? "返回上层" : "关闭"), b -> onClose()).bounds(x + w - 75, y + h - 28, 67, 20).build());
    }

    private boolean supported(String key) {
        return key.equals("minecraft:enchantments") || key.equals("minecraft:stored_enchantments") ||
                key.equals("minecraft:attribute_modifiers") ||
                key.equals("minecraft:enchantment_glint_override") || key.equals("minecraft:damage") ||
                key.equals("minecraft:max_damage") || key.equals("minecraft:max_stack_size") || key.equals("minecraft:repair_cost") ||
                key.equals("minecraft:custom_name") || key.equals("minecraft:item_name");
    }
    private static Tag parse(String text) { try { return TagParser.create(NbtOps.INSTANCE).parseFully(text); } catch (Exception ex) { throw new IllegalArgumentException(ex.getMessage()); } }

    private void openBasic() { minecraft.setScreen(new BasicItemScreen(this)); }
    private void edit(String key, TemplateScreen.Template template) {
        if (key.equals(AttributeModifierScreen.KEY)) { minecraft.setScreen(new AttributeModifierScreen(this)); return; }
        if (ComponentForms.KEYS.contains(key)) { ComponentForms.open(this,key); return; }
        if (key.endsWith("enchantments")) { minecraft.setScreen(new EnchantmentScreen(this,key)); return; }
        if (!present(key) || !supported(key)) return;
        Tag current = components().get(key);
        if (key.endsWith("custom_name") || key.endsWith("item_name")) {
            String text = current instanceof CompoundTag c ? c.getStringOr("text", "") : "";
            minecraft.setScreen(new InputScreen(this, "修改文本", text, value -> setComponent(key, parse("{text:" + quote(value) + "}"))));
        } else if (current instanceof ByteTag) {
            minecraft.setScreen(new InputScreen(this, "修改开关（true / false）", ((ByteTag) current).byteValue() != 0 ? "true" : "false",
                    value -> setComponent(key, ByteTag.valueOf(Boolean.parseBoolean(value)))));
        } else {
            minecraft.setScreen(new InputScreen(this, "修改数值", current.toString(), value -> setComponent(key, IntTag.valueOf(Integer.parseInt(value.trim())))));
        }
    }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private void synchronize() {
        String risk = StackData.riskReason(session.document);
        if (risk.isEmpty()) { session.sync(ok -> {}); return; }
        minecraft.setScreen(new RiskConfirm(this, risk));
    }
    /** 高风险数据同步前确认；实现 EditorLayer 以免会话在确认期间被判定为已关闭。 */
    private static final class RiskConfirm extends net.minecraft.client.gui.screens.ConfirmScreen implements EditorLayer {
        private final SimpleComponentScreen owner;
        RiskConfirm(SimpleComponentScreen owner, String reason) {
            super(ok -> { owner.minecraft.setScreen(owner); if (ok) owner.session.sync(done -> {}); },
                    Component.literal("当前数据可能导致游戏异常或崩溃，是否继续同步？"), Component.literal("原因：" + reason),
                    Component.literal("确定同步"), Component.literal("取消"));
            this.owner = owner;
        }
        @Override public EditSession session() { return owner.session; }
    }

    @Override public void tick() { if(revision != session.revision) rebuildWidgets(); }
    @Override public void onClose() { if (back != null) minecraft.setScreen(back); else session.close(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) { if (key.isEscape() || minecraft.options.keyInventory.matches(key)) { onClose(); return true; } return super.keyPressed(key); }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        ItemEditorScreen.panel(g, x, y, w, h); g.drawString(font, nested() ? "内部物品" : "简单模式", x + 8, y + 9, 0xFF303030, false);
        ItemStack preview = previewStack();
        String name = preview.isEmpty() ? "空物品" : preview.getHoverName().getString();
        g.drawString(font, font.plainSubstrByWidth(name + (nested() ? " · 同步会写回整个外层物品" : " · 复杂组件可切换到高级模式"), w - 110), x + 76, y + 9, 0xFF555555, false);
        int px = x + w - 26, py = y + 4;
        g.fill(px - 1, py - 1, px + 17, py + 17, 0xFF8B8B8B);
        if (!preview.isEmpty()) { g.renderItem(preview, px, py); g.renderItemDecorations(font, preview, px, py); }
        String feedback = !session.error.isEmpty() ? session.error : session.changed ? "原物品已发生变化，同步将覆盖目标位置"
                : session.status.isEmpty() ? session.editable ? "修改仅保留在副本中，点击“同步”写回物品。" : "多人 · 严格只读" : session.status;
        g.drawString(font, font.plainSubstrByWidth(feedback, w - 16), x + 8, y + h - 44,
                !session.error.isEmpty() || session.changed ? 0xFFAA2222 : 0xFF555555, false);
        super.render(g, mx, my, delta);
        if (!preview.isEmpty() && mx >= px && mx < px + 16 && my >= py && my < py + 16) {
            try { g.setTooltipForNextFrame(font, preview, mx, my); } catch (RuntimeException ignored) { }
        }
    }
    private ItemStack previewStack() {
        if (!nested() && advancedParent == null) return session.preview;
        try { return StackData.decode(root(), minecraft.level.registryAccess()); } catch (RuntimeException ex) { return ItemStack.EMPTY; }
    }

    private static final class InputScreen extends Screen implements EditorLayer {
        private final SimpleComponentScreen parent; private final String initial; private final java.util.function.Consumer<String> commit; private EditBox input; private int x,y,w,h;
        InputScreen(SimpleComponentScreen p, String title, String initial, java.util.function.Consumer<String> commit) { super(Component.literal(title)); this.parent=p; this.initial=initial; this.commit=commit; }
        public EditSession session(){return parent.session;}
        protected void init(){w=Math.min(500,width-16);h=150;x=(width-w)/2;y=(height-h)/2; input=addRenderableWidget(new EditBox(font,x+8,y+35,w-16,20,Component.literal("值")));input.setValue(initial);addRenderableWidget(Button.builder(Component.literal("确定"),b->{try{commit.accept(input.getValue());minecraft.setScreen(parent);}catch(Exception e){input.setHint(Component.literal("输入无效："+e.getMessage()));}}).bounds(x+8,y+h-28,65,20).build());addRenderableWidget(Button.builder(Component.literal("取消"),b->minecraft.setScreen(parent)).bounds(x+w-73,y+h-28,65,20).build());setInitialFocus(input);}
        public void render(GuiGraphics g,int mx,int my,float d){ItemEditorScreen.panel(g,x,y,w,h);g.drawString(font,title,x+8,y+12,0xFF303030,false);super.render(g,mx,my,d);}
        public boolean charTyped(CharacterEvent e){return super.charTyped(e);} public boolean keyPressed(KeyEvent e){if(e.isEscape()){minecraft.setScreen(parent);return true;}return super.keyPressed(e);}public boolean isPauseScreen(){return false;}public void onClose(){minecraft.setScreen(parent);}
    }

    private static final class BasicItemScreen extends Screen implements EditorLayer {
        private final SimpleComponentScreen parent; private EditBox id,count; private int x,y,w,h;
        BasicItemScreen(SimpleComponentScreen p){super(Component.literal("物品基础"));parent=p;} public EditSession session(){return parent.session;}
        protected void init(){w=420;h=180;x=(width-w)/2;y=(height-h)/2;id=addRenderableWidget(new EditBox(font,x+8,y+35,w-16,20,Component.literal("物品 ID")));id.setValue(parent.root().getStringOr("id","minecraft:stone"));count=addRenderableWidget(new EditBox(font,x+8,y+78,w-16,20,Component.literal("数量")));count.setValue(Integer.toString(parent.root().getIntOr("count",1)));addRenderableWidget(Button.builder(Component.literal("确定"),b->{try{CompoundTag r=parent.root().copy();r.putString("id",id.getValue());r.putInt("count",Integer.parseInt(count.getValue()));parent.setRoot(r);minecraft.setScreen(parent);}catch(Exception e){count.setHint(Component.literal("数量必须是整数"));}}).bounds(x+8,y+h-28,65,20).build());addRenderableWidget(Button.builder(Component.literal("取消"),b->minecraft.setScreen(parent)).bounds(x+w-73,y+h-28,65,20).build());}
        public void render(GuiGraphics g,int mx,int my,float d){ItemEditorScreen.panel(g,x,y,w,h);g.drawString(font,title,x+8,y+12,0xFF303030,false);g.drawString(font,"直接填写物品 ID 和数量，不需要 SNBT。",x+8,y+22,0xFF555555,false);super.render(g,mx,my,d);}public boolean isPauseScreen(){return false;}public void onClose(){minecraft.setScreen(parent);}public boolean keyPressed(KeyEvent e){if(e.isEscape()){onClose();return true;}return super.keyPressed(e);}
    }

}
