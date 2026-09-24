package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/** Lore 编辑：逐行增删改、上下移动。改动先存在本页草稿里，点"保存到副本"才写入物品副本。 */
final class LoreScreen extends Screen implements EditorLayer {
    private static final String KEY = "minecraft:lore";
    private static final int MAX_LINES = 256;
    private final SimpleComponentScreen parent;
    private ListTag lines;
    private int x, y, w, h, offset, pageSize;
    private String error = "";

    LoreScreen(SimpleComponentScreen parent, Tag existing) {
        super(Component.literal("Lore 描述"));
        this.parent = parent;
        this.lines = existing instanceof ListTag list ? list.copy() : new ListTag();
    }

    @Override public EditSession session() { return parent.session(); }
    private boolean writable() { return session().editable && !session().syncing; }

    @Override protected void init() {
        w = Math.min(680, width - 12); h = Math.min(430, height - 12); x = (width - w) / 2; y = (height - h) / 2;
        pageSize = Math.max(1, (h - 96) / 24);
        offset = Math.max(0, Math.min(offset, Math.max(0, (lines.size() - 1) / pageSize * pageSize)));
        for (int i = offset; i < Math.min(lines.size(), offset + pageSize); i++) {
            final int index = i;
            int by = y + 30 + (i - offset) * 24;
            Component text = DisplayForms.styled(lines.get(i), minecraft);
            Button line = button(Component.literal((i + 1) + ". ").append(text.getString().isEmpty() ? Component.literal("（空行）") : text),
                    x + 8, by, w - 214, () -> editLine(index));
            line.setTooltip(Tooltip.create(Component.literal("点击编辑这一行")));
            button(Component.literal("↑"), x + w - 202, by, 30, () -> move(index, -1)).active = writable() && index > 0;
            button(Component.literal("↓"), x + w - 168, by, 30, () -> move(index, 1)).active = writable() && index < lines.size() - 1;
            button(Component.literal("插入"), x + w - 134, by, 60, () -> insert(index)).active = writable() && lines.size() < MAX_LINES;
            button(Component.literal("删除"), x + w - 70, by, 62, () -> { lines.remove(index); rebuildWidgets(); }).active = writable();
        }
        button(Component.literal("新增一行"), x + 8, y + h - 52, 90, () -> insert(lines.size())).active = writable() && lines.size() < MAX_LINES;
        button(Component.literal("清空"), x + 102, y + h - 52, 56, () -> { lines = new ListTag(); rebuildWidgets(); }).active = writable() && !lines.isEmpty();
        button(Component.literal("上一页"), x + 162, y + h - 52, 58, () -> { offset -= pageSize; rebuildWidgets(); }).active = offset > 0;
        button(Component.literal("下一页"), x + 224, y + h - 52, 58, () -> { offset += pageSize; rebuildWidgets(); }).active = offset + pageSize < lines.size();
        button(Component.literal("保存到副本"), x + 8, y + h - 28, 100, this::save).active = writable();
        button(Component.literal("取消"), x + w - 70, y + h - 28, 62, this::onClose);
    }

    private Button button(Component label, int bx, int by, int bw, Runnable action) {
        return addRenderableWidget(Button.builder(label, b -> {
            try { error = ""; action.run(); } catch (RuntimeException ex) { error = ex.getMessage() == null ? "操作失败" : ex.getMessage(); }
        }).bounds(bx, by, bw, 20).build());
    }

    private void move(int index, int delta) {
        Tag moving = lines.remove(index);
        lines.add(index + delta, moving);
        rebuildWidgets();
    }

    private void insert(int index) {
        CompoundTag blank = new CompoundTag();
        blank.putString("text", "");
        blank.putBoolean("italic", false);
        blank.putString("color", "gray");
        DisplayForms.textForm(this, "新增 Lore 第 " + (index + 1) + " 行", blank, true, value -> lines.add(index, value));
    }

    private void editLine(int index) {
        DisplayForms.textForm(this, "Lore 第 " + (index + 1) + " 行", lines.get(index), true, value -> lines.set(index, value));
    }

    private void save() {
        if (!writable()) return;
        DisplayForms.save(parent, KEY, lines.copy());
        minecraft.setScreen(parent);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) { if (key.isEscape()) { onClose(); return true; } return super.keyPressed(key); }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        ItemEditorScreen.panel(g, x, y, w, h);
        g.drawString(font, "Lore 描述 · " + lines.size() + " 行", x + 8, y + 10, 0xFF303030, false);
        String hint = error.isEmpty() ? "点击某一行编辑文字和样式；“保存到副本”后回主界面点“同步”。" : error;
        g.drawString(font, font.plainSubstrByWidth(hint, w - 16), x + 8, y + h - 66, error.isEmpty() ? 0xFF555555 : 0xFFAA2222, false);
        super.render(g, mx, my, delta);
    }
}
