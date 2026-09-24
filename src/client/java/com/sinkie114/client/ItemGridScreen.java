package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * 简单模式 · 容器与内部物品：把潜影盒/箱子（container）、收纳袋（bundle_contents）、
 * 弩的已装填发射物（charged_projectiles）画成格子。
 * 单击选中格子，双击或"编辑"进入内部物品的简单模式；空格可放入物品。
 * 修改直接写入编辑副本，内部编辑完仍需回主界面点"同步"。
 */
final class ItemGridScreen extends Screen implements EditorLayer {
    static final String CONTAINER = "minecraft:container";
    static final Set<String> KEYS = Set.of(CONTAINER, "minecraft:bundle_contents", "minecraft:charged_projectiles");
    private static final int SLOT = 18, COLUMNS = 9, MAX_CONTAINER_SLOTS = 256;

    private final SimpleComponentScreen parent;
    private final String key;
    private final boolean slotted;           // container 按 slot 编号存；另外两个是有序列表
    private int extraRows;                   // container 手动扩展出来的行
    private int x, y, w, h, gridX, gridY, rowsPerPage, page, selected = -1;
    private String error = "";
    private Button editButton, putButton, deleteButton, countButton;

    private ItemGridScreen(SimpleComponentScreen parent, String key) {
        super(Component.literal(title(key)));
        this.parent = parent; this.key = key; this.slotted = key.equals(CONTAINER);
    }

    static void open(SimpleComponentScreen parent, String key) {
        parent.session().client.setScreen(new ItemGridScreen(parent, key));
    }

    private static String title(String key) {
        return switch (key) {
            case CONTAINER -> "容器内物品";
            case "minecraft:bundle_contents" -> "收纳袋内容";
            default -> "已装填发射物";
        };
    }

    @Override public EditSession session() { return parent.session(); }
    private boolean writable() { return session().editable && !session().syncing; }

    // ---------- 数据 ----------

    private ListTag entries() {
        return parent.components().get(key) instanceof ListTag list ? list : new ListTag();
    }

    /** 格子编号 → 该物品在列表里的下标。 */
    private Map<Integer, Integer> slotIndex() {
        var map = new HashMap<Integer, Integer>();
        ListTag list = entries();
        for (int i = 0; i < list.size(); i++) {
            if (slotted) {
                if (list.get(i) instanceof CompoundTag c) map.put(c.getIntOr("slot", -1), i);
            } else map.put(i, i);
        }
        return map;
    }

    private int slotCount() {
        int used = slotIndex().keySet().stream().mapToInt(i -> i + 1).max().orElse(0);
        if (!slotted) return used + 1;       // 列表末尾留一个空格用来追加
        int base = Math.max(27, (used + COLUMNS - 1) / COLUMNS * COLUMNS);
        return Math.min(MAX_CONTAINER_SLOTS, base + extraRows * COLUMNS);
    }

    /** 该格物品在文档里的路径（相对整个物品文档）。 */
    private List<Object> itemPath(int listIndex) {
        var path = new ArrayList<Object>(parent.path());
        path.add("components"); path.add(key); path.add(listIndex);
        if (slotted) path.add("item");
        return List.copyOf(path);
    }

    private CompoundTag itemTag(int listIndex) {
        Tag entry = entries().get(listIndex);
        if (slotted) return entry instanceof CompoundTag c ? c.getCompoundOrEmpty("item") : new CompoundTag();
        return entry instanceof CompoundTag c ? c : new CompoundTag();
    }

    private ItemStack stack(int listIndex) {
        try { return StackData.decode(itemTag(listIndex), minecraft.level.registryAccess()); }
        catch (RuntimeException ex) { return new ItemStack(Items.BARRIER); }
    }

    private void write(ListTag next) {
        ComponentForms.validate(session(), key, next);
        parent.setComponent(key, next);
    }

    // ---------- 操作 ----------

    private void put(int slot) {
        if (!writable()) return;
        minecraft.setScreen(new OptionPickerScreen(this, "放入物品", RegistryOptions.items(), id -> {
            CompoundTag item = new CompoundTag();
            item.putString("id", id); item.putInt("count", 1);
            ListTag next = entries().copy();
            if (slotted) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("slot", slot); entry.put("item", item);
                next.add(entry);
            } else next.add(item);
            try { write(next); selected = slot; error = ""; }
            catch (RuntimeException ex) { error = ex.getMessage(); }
            minecraft.setScreen(this);
        }));
    }

    private void delete(int slot) {
        Integer index = slotIndex().get(slot);
        if (!writable() || index == null) return;
        ListTag next = entries().copy();
        next.remove((int) index);
        write(next);
        rebuildWidgets();
    }

    private void edit(int slot) {
        Integer index = slotIndex().get(slot);
        if (index == null) return;
        minecraft.setScreen(new SimpleComponentScreen(session(), itemPath(index), this));
    }

    private void count(int slot) {
        Integer index = slotIndex().get(slot);
        if (index == null) return;
        CompoundTag draft = new CompoundTag();
        draft.putInt("count", itemTag(index).getIntOr("count", 1));
        var form = new EditorFormScreen(this, "数量", draft, result -> {
            CompoundTag item = itemTag(index).copy();
            item.putInt("count", result.getIntOr("count", 1));
            ListTag next = entries().copy();
            if (slotted) ((CompoundTag) next.get(index)).put("item", item); else next.set(index, item);
            write(next);
        });
        form.number("count", "数量（内部物品不能为 0）", 1, true, 1, Integer.MAX_VALUE);
        minecraft.setScreen(form);
    }

    // ---------- 界面 ----------

    @Override protected void init() {
        w = Math.min(420, width - 12); h = Math.min(330, height - 12); x = (width - w) / 2; y = (height - h) / 2;
        rowsPerPage = Math.max(1, (h - 120) / SLOT);
        int rows = (slotCount() + COLUMNS - 1) / COLUMNS;
        page = Math.max(0, Math.min(page, (rows - 1) / rowsPerPage));
        gridX = x + (w - COLUMNS * SLOT) / 2; gridY = y + 30;

        int by = y + h - 52;
        editButton = button(session().editable ? "编辑" : "查看", x + 8, by, 56, () -> edit(selected));
        countButton = button("数量", x + 68, by, 56, () -> count(selected));
        putButton = button("放入物品", x + 128, by, 76, () -> put(selected));
        deleteButton = button("删除", x + 208, by, 56, () -> delete(selected));
        if (slotted) button("加一行", x + w - 64, by, 56, () -> { extraRows++; rebuildWidgets(); }).active = slotCount() < MAX_CONTAINER_SLOTS;
        button("上一页", x + 8, y + h - 28, 58, () -> { page--; rebuildWidgets(); }).active = page > 0;
        button("下一页", x + 70, y + h - 28, 58, () -> { page++; rebuildWidgets(); }).active = (page + 1) * rowsPerPage < rows;
        button("返回", x + w - 70, y + h - 28, 62, this::onClose);
        updateButtons();
    }

    private Button button(String label, int bx, int by, int bw, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> {
            try { error = ""; action.run(); } catch (RuntimeException ex) { error = ex.getMessage() == null ? "操作失败" : ex.getMessage(); }
        }).bounds(bx, by, bw, 20).build());
    }

    private void updateButtons() {
        if (editButton == null) return;
        boolean has = selected >= 0 && slotIndex().containsKey(selected);
        editButton.active = has;
        countButton.active = has && writable();
        deleteButton.active = has && writable();
        putButton.active = selected >= 0 && !has && writable() && selected < slotCount();
    }

    private int slotAt(double mx, double my) {
        int col = (int) Math.floor((mx - gridX) / SLOT), row = (int) Math.floor((my - gridY) / SLOT);
        if (mx < gridX || my < gridY || col >= COLUMNS || row >= rowsPerPage) return -1;
        int slot = (page * rowsPerPage + row) * COLUMNS + col;
        return slot < slotCount() ? slot : -1;
    }

    /** 测试用：某格中心的屏幕坐标（需在当前页）。 */
    double[] slotCenter(int slot) {
        int local = slot - page * rowsPerPage * COLUMNS;
        return new double[]{gridX + (local % COLUMNS) * SLOT + SLOT / 2.0, gridY + (local / COLUMNS) * SLOT + SLOT / 2.0};
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int slot = slotAt(event.x(), event.y());
        if (slot >= 0) {
            selected = slot; error = "";
            if (doubleClick || event.button() == 1) {
                if (slotIndex().containsKey(slot)) edit(slot); else put(slot);
            }
            updateButtons();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public void tick() { updateButtons(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) { if (key.isEscape()) { onClose(); return true; } return super.keyPressed(key); }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        ItemEditorScreen.panel(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 10, 0xFF303030, false);
        var index = slotIndex();
        int total = slotCount();
        int hovered = slotAt(mx, my);
        for (int row = 0; row < rowsPerPage; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int slot = (page * rowsPerPage + row) * COLUMNS + col;
                if (slot >= total) break;
                int sx = gridX + col * SLOT, sy = gridY + row * SLOT;
                // 原版风格的凹陷格子
                g.fill(sx, sy, sx + SLOT, sy + SLOT, 0xFF373737);
                g.fill(sx + 1, sy + 1, sx + SLOT, sy + SLOT, 0xFFFFFFFF);
                g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0xFF8B8B8B);
                if (slot == selected) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0xFF5C6786);
                Integer listIndex = index.get(slot);
                if (listIndex != null) {
                    ItemStack stack = stack(listIndex);
                    g.renderItem(stack, sx + 1, sy + 1);
                    g.renderItemDecorations(font, stack, sx + 1, sy + 1);
                }
                if (slot == hovered) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x80FFFFFF);
            }
        }
        String hint = !error.isEmpty() ? error
                : selected >= 0 ? (slotted ? "槽位 " + selected : "第 " + (selected + 1) + " 个") + (index.containsKey(selected) ? "" : "（空）")
                : "单击选中格子；双击或右键：有物品则编辑，空格则放入物品。";
        g.drawString(font, font.plainSubstrByWidth(hint, w - 16), x + 8, y + h - 66, error.isEmpty() ? 0xFF555555 : 0xFFAA2222, false);
        super.render(g, mx, my, delta);
        if (hovered >= 0 && index.containsKey(hovered)) {
            try { g.setTooltipForNextFrame(font, stack(index.get(hovered)), mx, my); } catch (RuntimeException ignored) { }
        }
    }
}
