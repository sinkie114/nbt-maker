package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.*;
import java.util.function.Consumer;

/** Edits only the selected modifier; all other entries and optional display data remain intact. */
public final class AttributeModifierScreen extends Screen implements EditorLayer {
    static final String KEY = "minecraft:attribute_modifiers";
    private final SimpleComponentScreen parent;
    private int x, y, w, h, offset, pageSize, revision;
    private String message = "";

    public AttributeModifierScreen(SimpleComponentScreen parent) {
        super(Component.literal("属性修饰符"));
        this.parent = parent;
    }

    @Override public EditSession session() { return parent.session(); }
    private boolean writable() { return session().editable && !session().syncing; }
    private ListTag entries() {
        Tag value = parent.components().get(KEY);
        if (value == null) return new ListTag();
        if (value instanceof ListTag list) return list;
        throw new IllegalArgumentException("该组件不是属性列表，请在高级模式检查数据。");
    }

    @Override protected void init() {
        w = Math.min(700, width - 12); h = Math.min(430, height - 12);
        x = (width - w) / 2; y = (height - h) / 2;
        revision = session().revision;
        pageSize = Math.max(1, (h - 98) / 38);
        ListTag list;
        try { list = entries(); }
        catch (RuntimeException ex) { list = new ListTag(); message = ex.getMessage(); }
        offset = Math.max(0, Math.min(offset, Math.max(0, (list.size() - 1) / pageSize * pageSize)));
        for (int i = offset; i < Math.min(list.size(), offset + pageSize); i++) {
            final int index = i;
            Tag value = list.get(i);
            int ry = y + 32 + (i - offset) * 38;
            if (value instanceof CompoundTag entry) {
                String type = entry.getStringOr("type", "?");
                String amount = entry.get("amount") instanceof NumericTag n ? Double.toString(n.doubleValue()) : "?";
                String details = operationName(entry.getStringOr("operation", "add_value")) + " " + amount
                        + " · " + slotName(entry.getStringOr("slot", "any"));
                button(attributeName(type), x + 8, ry, w - 150, () -> edit(index, entry))
                        .setTooltip(Tooltip.create(Component.literal(type + "\n" + entry.getStringOr("id", "") + "\n" + details)));
                button(session().editable ? "编辑" : "查看", x + w - 138, ry, 62, () -> edit(index, entry));
            } else {
                button("条目 " + (index + 1) + "：格式错误", x + 8, ry, w - 150, () -> {})
                        .active = false;
            }
            button("删除", x + w - 70, ry, 62, () -> remove(index)).active = writable();
        }
        button("新增修饰符", x + 8, y + h - 28, 96, () -> {
            if (!writable()) return;
            minecraft.setScreen(new ChoiceScreen(this, "选择属性", attributeChoices(), choice -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("type", choice);
                entry.putString("id", "nbt-maker:modifier_" + UUID.randomUUID().toString().replace("-", ""));
                entry.putDouble("amount", 1);
                entry.putString("operation", "add_value");
                entry.putString("slot", "mainhand");
                edit(-1, entry);
            }));
        }).active = writable() && (parent.components().get(KEY) == null || parent.components().get(KEY) instanceof ListTag);
        button("上一页", x + 110, y + h - 28, 58, () -> { offset -= pageSize; rebuildWidgets(); }).active = offset > 0;
        button("下一页", x + 172, y + h - 28, 58, () -> { offset += pageSize; rebuildWidgets(); }).active = offset + pageSize < list.size();
        button("返回", x + w - 70, y + h - 28, 62, this::onClose);
    }

    private Button button(String text, int bx, int by, int bw, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(bx, by, bw, 20).build());
    }
    private void edit(int index, CompoundTag entry) { minecraft.setScreen(new ModifierForm(this, index, entry)); }
    private void remove(int index) {
        if (!writable()) return;
        ListTag next = entries().copy();
        next.remove(index);
        parent.setComponent(KEY, next);
        message = "已删除；点击“同步”写回物品";
        rebuildWidgets();
    }

    private List<Choice> attributeChoices() {
        return session().client.level.registryAccess().lookupOrThrow(Registries.ATTRIBUTE).listElements()
                .map(holder -> {
                    String id = holder.key().identifier().toString();
                    return new Choice(id, attributeName(id), id);
                }).sorted(Comparator.comparing(Choice::value)).toList();
    }

    private String attributeName(String id) {
        try {
            var registry = session().client.level.registryAccess().lookupOrThrow(Registries.ATTRIBUTE);
            var holder = registry.get(net.minecraft.resources.ResourceKey.create(Registries.ATTRIBUTE, Identifier.parse(id)));
            if (holder.isPresent()) {
                String key = holder.get().value().getDescriptionId();
                String name = Component.translatable(key).getString();
                if (!name.equals(key)) return name;
            }
        } catch (RuntimeException ignored) { }
        return id;
    }

    private static String operationName(String id) {
        return switch (id) {
            case "add_value" -> "直接加值";
            case "add_multiplied_base" -> "按基础值增加";
            case "add_multiplied_total" -> "乘以最终值";
            default -> id;
        };
    }
    private static String slotName(String id) {
        return switch (id) {
            case "any" -> "任意部位"; case "mainhand" -> "主手"; case "offhand" -> "副手";
            case "hand" -> "双手"; case "feet" -> "脚部"; case "legs" -> "腿部";
            case "chest" -> "胸部"; case "head" -> "头部"; case "armor" -> "盔甲";
            case "body" -> "身体（动物装备）"; case "saddle" -> "鞍";
            default -> id;
        };
    }

    @Override public void tick() { if (revision != session().revision) rebuildWidgets(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) {
        if (key.isEscape() || minecraft.options.keyInventory.matches(key)) { onClose(); return true; }
        return super.keyPressed(key);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        ItemEditorScreen.panel(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 10, 0xFF303030, false);
        try {
            ListTag list = entries();
            g.drawString(font, "共 " + list.size() + " 条", x + w - 92, y + 10, 0xFF555555, false);
            if (list.isEmpty()) g.drawString(font, "暂无修饰符，点击下方“新增修饰符”选择属性。", x + 8, y + 35, 0xFF555555, false);
            for (int i = offset; i < Math.min(list.size(), offset + pageSize); i++) {
                if (!(list.get(i) instanceof CompoundTag entry)) continue;
                String amount = entry.get("amount") instanceof NumericTag n ? Double.toString(n.doubleValue()) : "?";
                String details = operationName(entry.getStringOr("operation", "add_value")) + " " + amount
                        + " · " + slotName(entry.getStringOr("slot", "any"));
                g.drawString(font, font.plainSubstrByWidth(details, w - 16), x + 10, y + 55 + (i - offset) * 38, 0xFF555555, false);
            }
        } catch (RuntimeException ignored) { }
        g.drawString(font, font.plainSubstrByWidth(message.isEmpty() ? "修改保留在副本中，返回后点击“同步”。" : message, w - 16),
                x + 8, y + h - 45, 0xFF555555, false);
        super.render(g, mx, my, delta);
    }

    private record Choice(String value, String label, String detail) {}

    /** Search rebuilds only result buttons, leaving the focused search field and cursor intact. */
    private static final class ChoiceScreen extends Screen implements EditorLayer {
        private final Screen parent;
        private final List<Choice> all;
        private final Consumer<String> select;
        private final List<Button> results = new ArrayList<>();
        private String query = "";
        private int offset, x, y, w, h, pageSize, matches;
        private Button previous, next;

        ChoiceScreen(Screen parent, String title, List<Choice> choices, Consumer<String> select) {
            super(Component.literal(title)); this.parent = parent; this.all = choices; this.select = select;
        }
        @Override public EditSession session() { return ((EditorLayer) parent).session(); }
        @Override protected void init() {
            w = Math.min(640, width - 12); h = Math.min(430, height - 12); x = (width - w) / 2; y = (height - h) / 2;
            pageSize = Math.max(1, (h - 100) / 24);
            results.clear();
            EditBox search = addRenderableWidget(new EditBox(font, x + 8, y + 29, w - 16, 20, Component.literal("搜索选项")));
            search.setMaxLength(256); search.setHint(Component.literal("搜索名称、ID 或 mod 命名空间")); search.setValue(query);
            search.setResponder(value -> { query = value; offset = 0; refreshResults(); });
            previous = addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { offset -= pageSize; refreshResults(); })
                    .bounds(x + 8, y + h - 28, 62, 20).build());
            next = addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { offset += pageSize; refreshResults(); })
                    .bounds(x + 76, y + h - 28, 62, 20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(x + w - 70, y + h - 28, 62, 20).build());
            refreshResults();
            setInitialFocus(search);
        }
        private void refreshResults() {
            if (previous == null) return;
            results.forEach(this::removeWidget); results.clear();
            String needle = query.toLowerCase(Locale.ROOT);
            List<Choice> filtered = all.stream().filter(c -> (c.label() + " " + c.value() + " " + c.detail()).toLowerCase(Locale.ROOT).contains(needle)).toList();
            matches = filtered.size();
            offset = Math.max(0, Math.min(offset, Math.max(0, (matches - 1) / pageSize * pageSize)));
            for (int i = offset; i < Math.min(matches, offset + pageSize); i++) {
                Choice choice = filtered.get(i);
                Button b = addRenderableWidget(Button.builder(Component.literal(choice.label() + " · " + choice.value()), button -> {
                    if (!session().editable || session().syncing) return;
                    select.accept(choice.value());
                }).bounds(x + 8, y + 57 + (i - offset) * 24, w - 16, 20)
                        .tooltip(Tooltip.create(Component.literal(choice.detail()))).build());
                b.active = session().editable && !session().syncing;
                results.add(b);
            }
            previous.active = offset > 0; next.active = offset + pageSize < matches;
        }
        @Override public void onClose() { minecraft.setScreen(parent); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public boolean keyPressed(KeyEvent key) { if (key.isEscape()) { onClose(); return true; } return super.keyPressed(key); }
        @Override public void render(GuiGraphics g, int mx, int my, float delta) {
            ItemEditorScreen.panel(g, x, y, w, h);
            g.drawString(font, title, x + 8, y + 10, 0xFF303030, false);
            g.drawString(font, matches == 0 ? "没有匹配项" : "共 " + matches + " 项", x + 8, y + h - 44, 0xFF555555, false);
            super.render(g, mx, my, delta);
        }
    }

    private static final class ModifierForm extends Screen implements EditorLayer {
        private final AttributeModifierScreen parent;
        private final int index;
        private final CompoundTag original;
        private String attribute, amountText, idText, operation, slot, error = "";
        private int x, y, w, h, spacing;

        ModifierForm(AttributeModifierScreen parent, int index, CompoundTag entry) {
            super(Component.literal(index < 0 ? "新增属性修饰符" : "编辑属性修饰符"));
            this.parent = parent; this.index = index; original = entry.copy();
            attribute = entry.getStringOr("type", "");
            amountText = entry.get("amount") instanceof NumericTag n ? Double.toString(n.doubleValue()) : "";
            idText = entry.getStringOr("id", "nbt-maker:modifier_" + UUID.randomUUID().toString().replace("-", ""));
            operation = entry.getStringOr("operation", "add_value");
            slot = entry.getStringOr("slot", "any");
        }
        @Override public EditSession session() { return parent.session(); }
        @Override protected void init() {
            w = Math.min(540, width - 12); h = Math.min(338, height - 12); x = (width - w) / 2; y = (height - h) / 2;
            spacing = (h - 82) / 5;
            button(parent.attributeName(attribute), row(0), () -> minecraft.setScreen(new ChoiceScreen(this, "选择属性", parent.attributeChoices(), chosen -> {
                attribute = chosen; minecraft.setScreen(this);
            }))).setTooltip(Tooltip.create(Component.literal(attribute)));
            EditBox amount = input("数值", amountText, row(1), value -> amountText = value);
            amount.setTooltip(Tooltip.create(Component.literal("可填写负数或小数；比例运算中 0.1 表示 10%。")));
            button(operationName(operation), row(2), () -> {
                List<Choice> choices = Arrays.stream(AttributeModifier.Operation.values()).map(op -> {
                    String id = op.getSerializedName();
                    String help = switch (op) {
                        case ADD_VALUE -> "例如填 5：增加 5 点。";
                        case ADD_MULTIPLIED_BASE -> "例如填 0.1：增加基础值的 10%。";
                        case ADD_MULTIPLIED_TOTAL -> "例如填 0.1：将计算结果乘以 1.1。";
                    };
                    return new Choice(id, operationName(id), help);
                }).toList();
                minecraft.setScreen(new ChoiceScreen(this, "选择运算方式", choices, chosen -> { operation = chosen; minecraft.setScreen(this); }));
            });
            button(slotName(slot), row(3), () -> {
                List<Choice> choices = Arrays.stream(EquipmentSlotGroup.values())
                        .map(group -> new Choice(group.getSerializedName(), slotName(group.getSerializedName()), "在所选装备部位生效")).toList();
                minecraft.setScreen(new ChoiceScreen(this, "选择生效部位", choices, chosen -> { slot = chosen; minecraft.setScreen(this); }));
            });
            input("修饰符 ID", idText, row(4), value -> idText = value)
                    .setTooltip(Tooltip.create(Component.literal("新增时自动生成唯一 ID，通常无需修改。")));
            addRenderableWidget(Button.builder(Component.literal("保存到副本"), b -> save())
                    .bounds(x + 8, y + h - 28, 100, 20).build()).active = parent.writable();
            addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose()).bounds(x + w - 70, y + h - 28, 62, 20).build());
            setInitialFocus(amount);
        }
        private int row(int index) { return y + 32 + index * spacing; }
        private Button button(String label, int by, Runnable action) {
            Button b = addRenderableWidget(Button.builder(Component.literal(label), q -> action.run()).bounds(x + 8, by, w - 16, 18).build());
            b.active = parent.writable(); return b;
        }
        private EditBox input(String label, String initial, int by, Consumer<String> changed) {
            EditBox box = addRenderableWidget(new EditBox(font, x + 8, by, w - 16, 18, Component.literal(label)));
            box.setMaxLength(1024); box.setValue(initial); box.setResponder(changed); box.setEditable(parent.writable());
            return box;
        }
        private void save() {
            if (!parent.writable()) return;
            try {
                double amount;
                try { amount = Double.parseDouble(amountText.trim()); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("数值请输入数字，例如 5、-2 或 0.1。"); }
                if (!Double.isFinite(amount)) throw new IllegalArgumentException("数值必须是有限数字。");
                Identifier id = Identifier.tryParse(idText.trim());
                if (id == null) throw new IllegalArgumentException("修饰符 ID 格式无效，例如 nbt-maker:attack_bonus。");
                CompoundTag changed = original.copy();
                changed.putString("type", attribute); changed.putString("id", id.toString());
                if (!(original.get("amount") instanceof NumericTag n) || Double.compare(n.doubleValue(), amount) != 0) changed.putDouble("amount", amount);
                if (!operation.equals(original.getStringOr("operation", ""))) changed.putString("operation", operation);
                if (!slot.equals(original.getStringOr("slot", "any"))) changed.putString("slot", slot);
                // Decode the complete proposed entry before touching the shared draft.
                ItemAttributeModifiers.Entry.CODEC.parse(session().client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), changed).getOrThrow();
                ListTag next = parent.entries().copy();
                if (index < 0) next.add(changed);
                else {
                    if (index >= next.size() || !next.get(index).equals(original)) throw new IllegalArgumentException("该条目已变化，请返回后重新编辑。");
                    next.set(index, changed);
                }
                parent.parent.setComponent(KEY, next);
                parent.message = "已保存到副本；返回后点击“同步”";
                minecraft.setScreen(parent);
            } catch (RuntimeException ex) { error = ex.getMessage() == null ? "无法保存，请检查输入。" : ex.getMessage(); }
        }
        @Override public void onClose() { minecraft.setScreen(parent); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public boolean keyPressed(KeyEvent key) {
            if (key.isEscape()) { onClose(); return true; }
            if (key.hasControlDown() && key.isConfirmation()) { save(); return true; }
            return super.keyPressed(key);
        }
        @Override public void render(GuiGraphics g, int mx, int my, float delta) {
            ItemEditorScreen.panel(g, x, y, w, h);
            g.drawString(font, title, x + 8, y + 8, 0xFF303030, false);
            String[] labels = {"属性", "数值", "运算方式", "生效部位", "修饰符 ID（已自动填写）"};
            for (int i = 0; i < labels.length; i++) g.drawString(font, labels[i], x + 8, row(i) - 10, 0xFF555555, false);
            g.drawString(font, font.plainSubstrByWidth(error, w - 16), x + 8, y + h - 44, 0xFFAA2222, false);
            super.render(g, mx, my, delta);
            if (!error.isEmpty() && my >= y + h - 48 && my < y + h - 30) g.setTooltipForNextFrame(font, Component.literal(error), mx, my);
        }
    }
}
