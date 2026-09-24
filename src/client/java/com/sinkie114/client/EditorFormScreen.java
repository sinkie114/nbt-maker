package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/** Local form transaction. Navigation/resizing preserves input; cancel never touches the item. */
final class EditorFormScreen extends Screen implements EditorLayer {
    final CompoundTag draft;
    private final Screen parent;
    private final Consumer<CompoundTag> commit;
    private final List<Field> fields = new ArrayList<>();
    private int x, y, w, h, offset, pageSize;
    private String error = "";
    private record Field(String label, String help, Supplier<String> display, Consumer<Button> action,
                         TextValue text, boolean inspect) {}
    private static final class TextValue {
        final String initial;
        String current;
        final BiConsumer<CompoundTag, String> apply;
        TextValue(String initial, BiConsumer<CompoundTag, String> apply) { this.initial = initial; current = initial; this.apply = apply; }
    }
    EditorFormScreen(Screen parent, String title, CompoundTag value, Consumer<CompoundTag> commit) {
        super(Component.literal(title)); this.parent = parent; draft = value.copy(); this.commit = commit;
    }
    @Override public EditSession session() { return ((EditorLayer)parent).session(); }
    boolean writable() { return session().editable && !session().syncing; }
    void refreshFields() { rebuildWidgets(); }
    EditorFormScreen input(String label, String help, String initial, BiConsumer<CompoundTag,String> apply) {
        fields.add(new Field(label, help, null, null, new TextValue(initial, apply), false)); return this;
    }
    EditorFormScreen number(String key, String label, double fallback, boolean integer, double minimum, double maximum) {
        Tag value = draft.get(key);
        String initial = value instanceof NumericTag n ? integer ? Long.toString(n.longValue()) : Double.toString(n.doubleValue())
                : integer ? Long.toString((long)fallback) : Double.toString(fallback);
        return input(label, integer ? "填写整数" : "可填写小数", initial, (data, text) -> {
            double parsed = numeric(text, label, minimum, maximum);
            if (integer) {
                if (parsed != Math.rint(parsed)) throw new IllegalArgumentException(label + "需要整数。");
                data.putInt(key, (int)parsed);
            } else data.putFloat(key, (float)parsed);
        });
    }
    static double numeric(String text, String label, double min, double max) {
        double value;
        try { value = Double.parseDouble(text.trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + "请输入数字。"); }
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(label + "范围：" + min + " 至 " + max);
        return value;
    }
    EditorFormScreen toggle(String key, String label, boolean fallback) {
        fields.add(new Field(label, "", () -> draft.getBooleanOr(key, fallback) ? "开启" : "关闭", button -> {
            draft.putBoolean(key, !draft.getBooleanOr(key, fallback));
            button.setMessage(Component.literal(draft.getBooleanOr(key, fallback) ? "开启" : "关闭"));
        }, null, false)); return this;
    }
    EditorFormScreen choice(String label, Supplier<String> current, Supplier<List<OptionPickerScreen.Option>> choices, Consumer<String> change) {
        fields.add(new Field(label, "", current, b -> minecraft.setScreen(new OptionPickerScreen(this, "选择" + label, choices.get(), value -> {
            change.accept(value); minecraft.setScreen(this);
        })), null, false)); return this;
    }
    EditorFormScreen action(String label, Supplier<String> text, Runnable action, boolean inspect) {
        fields.add(new Field(label, "", text, b -> action.run(), null, inspect)); return this;
    }
    String inputValue(String label) { return fields.stream().filter(f -> f.label().equals(label) && f.text() != null).findFirst().orElseThrow().text().current; }
    @Override protected void init() {
        w = Math.min(560, width - 12); h = Math.min(400, height - 12); x = (width - w) / 2; y = (height - h) / 2;
        pageSize = Math.max(1, (h - 82) / 38); offset = Math.max(0, Math.min(offset, Math.max(0, (fields.size() - 1) / pageSize * pageSize)));
        for (int i = offset; i < Math.min(fields.size(), offset + pageSize); i++) {
            Field field = fields.get(i); int by = y + 34 + (i - offset) * 38;
            if (field.text() != null) {
                EditBox box = addRenderableWidget(new EditBox(font, x + 8, by, w - 16, 20, Component.literal(field.label())));
                box.setMaxLength(32767); box.setValue(field.text().current); box.setEditable(writable());
                box.setResponder(value -> { field.text().current = value; error = ""; });
                if (!field.help().isEmpty()) box.setTooltip(Tooltip.create(Component.literal(field.help())));
            } else {
                Button b = addRenderableWidget(Button.builder(Component.literal(field.display().get()), button -> {
                    try { error = ""; field.action().accept(button); }
                    catch (RuntimeException ex) { error = ex.getMessage() == null ? "无法打开此选项" : ex.getMessage(); }
                })
                        .bounds(x + 8, by, w - 16, 20).build());
                b.active = field.inspect() || writable();
            }
        }
        addRenderableWidget(Button.builder(Component.literal("保存到副本"), b -> save()).bounds(x + 8, y + h - 28, 100, 20).build()).active = writable();
        if (fields.size() > pageSize) {
            addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { offset -= pageSize; rebuildWidgets(); }).bounds(x + 112, y + h - 28, 56, 20).build()).active = offset > 0;
            addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { offset += pageSize; rebuildWidgets(); }).bounds(x + 172, y + h - 28, 56, 20).build()).active = offset + pageSize < fields.size();
        }
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose()).bounds(x + w - 70, y + h - 28, 62, 20).build());
    }
    private void save() {
        if (!writable()) return;
        try {
            CompoundTag result = draft.copy();
            for (Field field : fields) if (field.text() != null && !field.text().initial.equals(field.text().current))
                field.text().apply.accept(result, field.text().current);
            commit.accept(result); minecraft.setScreen(parent);
        } catch (RuntimeException ex) { error = ex.getMessage() == null ? "输入无效" : ex.getMessage(); }
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) {
        if (key.isEscape()) { onClose(); return true; }
        if (key.hasControlDown() && key.isConfirmation()) { save(); return true; }
        return super.keyPressed(key);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        ItemEditorScreen.panel(g, x, y, w, h); g.drawString(font, title, x + 8, y + 8, 0xFF303030, false);
        for (int i = offset; i < Math.min(fields.size(), offset + pageSize); i++)
            g.drawString(font, fields.get(i).label(), x + 8, y + 24 + (i - offset) * 38, 0xFF555555, false);
        g.drawString(font, font.plainSubstrByWidth(error.isEmpty() ? "保存后返回主界面，点击“同步”写回物品。" : error, w - 16), x + 8, y + h - 44,
                error.isEmpty() ? 0xFF555555 : 0xFFAA2222, false);
        super.render(g, mx, my, delta);
        if (!error.isEmpty() && my >= y + h - 48 && my < y + h - 30) g.setTooltipForNextFrame(font, Component.literal(error), mx, my);
    }
}
