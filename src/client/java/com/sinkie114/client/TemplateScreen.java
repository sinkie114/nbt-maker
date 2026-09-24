package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Valid 1.21.11 examples. The raw editor remains available for any registered mod component. */
public final class TemplateScreen extends Screen implements EditorLayer {
    record Template(String name, String key, String value) {}
    static final List<Template> ALL = List.of(
            new Template("自定义名称（支持文本样式）", "custom_name", "{text:\"自定义物品\",color:\"gold\",italic:false}"),
            new Template("Lore 描述", "lore", "[{text:\"第一行描述\",color:\"gray\",italic:false}]"),
            new Template("物品名称", "item_name", "\"物品名称\""),
            new Template("物品模型", "item_model", "\"minecraft:diamond_sword\""),
            new Template("自定义模型数据", "custom_model_data", "{floats:[1.0f],flags:[],strings:[],colors:[]}"),
            new Template("提示显示/隐藏组件", "tooltip_display", "{hide_tooltip:false,hidden_components:[]}"),
            new Template("染色颜色（RGB 整数）", "dyed_color", "16711680"),
            new Template("附魔光效", "enchantment_glint_override", "true"),
            new Template("稀有度", "rarity", "\"epic\""),
            new Template("附魔（可继续新增附魔 ID）", "enchantments", "{\"minecraft:sharpness\":10}"),
            new Template("存储附魔", "stored_enchantments", "{\"minecraft:unbreaking\":10}"),
            new Template("属性修饰符", "attribute_modifiers", "[{type:\"minecraft:attack_damage\",id:\"nbt-maker:damage\",amount:20.0d,operation:\"add_value\",slot:\"mainhand\"}]"),
            new Template("损耗/耐久", "damage", "0"),
            new Template("最大耐久", "max_damage", "1000"),
            new Template("最大堆叠", "max_stack_size", "64"),
            new Template("不可破坏", "unbreakable", "{}"),
            new Template("修复花费", "repair_cost", "0"),
            new Template("食物", "food", "{nutrition:8,saturation:9.6f,can_always_eat:true}"),
            new Template("可食用/饮用与使用时间", "consumable", "{consume_seconds:1.6f,animation:\"eat\",sound:\"minecraft:entity.generic.eat\",has_consume_particles:true,on_consume_effects:[]}"),
            new Template("使用冷却", "use_cooldown", "{seconds:1.0f,cooldown_group:\"nbt-maker:custom\"}"),
            new Template("使用后转换物品", "use_remainder", "{id:\"minecraft:glass_bottle\",count:1}"),
            new Template("药水与自定义效果", "potion_contents", "{potion:\"minecraft:healing\",custom_effects:[]}"),
            new Template("药水持续时间倍率", "potion_duration_scale", "1.0f"),
            new Template("工具", "tool", "{rules:[],default_mining_speed:1.0f,damage_per_block:1}"),
            new Template("装备部位", "equippable", "{slot:\"head\"}"),
            new Template("容器内物品", "container", "[{slot:0,item:{id:\"minecraft:diamond\",count:1}}]"),
            new Template("束口袋内容", "bundle_contents", "[{id:\"minecraft:diamond\",count:1}]"),
            new Template("已装填发射物", "charged_projectiles", "[{id:\"minecraft:arrow\",count:1}]"),
            new Template("烟花火箭", "fireworks", "{flight_duration:1,explosions:[]}"),
            new Template("烟花爆炸", "firework_explosion", "{shape:\"small_ball\",colors:[I;16711680],fade_colors:[I;],has_trail:false,has_twinkle:false}"),
            new Template("方块实体数据", "block_entity_data", "{id:\"minecraft:shulker_box\"}"),
            new Template("自定义数据", "custom_data", "{example:1,enabled:true,message:\"Hello\",nested:{},items:[],bytes:[B;],ints:[I;],longs:[L;]}")
    );
    private final ItemEditorScreen parent;
    private final EditorPage category;
    private int offset, x, y, w, h, pageSize;
    private List<Template> choices;
    public TemplateScreen(ItemEditorScreen parent, EditorPage category) {
        super(Component.literal("组件模板")); this.parent = parent; this.category = category;
    }
    @Override public EditSession session() { return parent.session(); }
    @Override protected void init() {
        w = Math.min(600, width - 16); h = Math.min(420, height - 16); x = (width - w) / 2; y = (height - h) / 2;
        choices = ALL.stream().sorted(Comparator.comparing(t -> !category.accepts("minecraft:" + t.key))).toList();
        pageSize = Math.max(1, (h - 76) / 23);
        for (int i = 0; i < pageSize && offset + i < choices.size(); i++) {
            Template t = choices.get(offset + i);
            addRenderableWidget(Button.builder(Component.literal(t.name), b -> {
                minecraft.setScreen(new ValueScreen(parent, "新增组件模板", "minecraft:" + t.key, t.value, true, false, session().editable,
                        (key, value) -> parent.addComponent(key, value)));
            }).bounds(x + 8, y + 27 + i * 23, w - 16, 20).tooltip(Tooltip.create(Component.literal("minecraft:" + t.key + "\n" + t.value))).build());
        }
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { offset = Math.max(0, offset - pageSize); rebuildWidgets(); }).bounds(x + 8, y + h - 27, 75, 20).build()).active = offset > 0;
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { offset += pageSize; rebuildWidgets(); }).bounds(x + 88, y + h - 27, 75, 20).build()).active = offset + pageSize < choices.size();
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(x + w - 83, y + h - 27, 75, 20).build());
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean keyPressed(KeyEvent key) {
        if (key.isEscape() || minecraft.options.keyInventory.matches(key)) { session().close(); return true; }
        return super.keyPressed(key);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        ItemEditorScreen.panel(g, x, y, w, h);
        g.drawString(font, "组件模板 · " + category.title + "（已有组件请直接编辑）", x + 8, y + 10, 0xFF303030, false);
        super.render(g, mx, my, delta);
    }
}
