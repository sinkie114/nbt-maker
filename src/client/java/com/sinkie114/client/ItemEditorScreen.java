package com.sinkie114.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ItemEditorScreen extends Screen implements EditorLayer {
    private static final Identifier CHEST = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int ROW = 14;
    private final EditSession session;
    private final ItemEditorScreen parent;
    private final List<Object> rootPath;
    private final Set<List<Object>> expanded = new HashSet<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Button> editingButtons = new ArrayList<>();
    private EditorPage page = EditorPage.STACK;
    private List<Object> selected;
    private EditBox search;
    private String query = "";
    private int x, y, w, h, treeTop, treeBottom, scroll, horizontal, seenRevision = -1;
    private Button syncButton;
    private boolean draggingBar;
    private ItemStack nestedPreview = ItemStack.EMPTY;

    public ItemEditorScreen(EditSession session, ItemEditorScreen parent, List<Object> rootPath) {
        super(Component.literal("物品堆数据调试器")); this.session = session; this.parent = parent; this.rootPath = List.copyOf(rootPath);
        expanded.add(this.rootPath); expanded.add(StackData.child(this.rootPath, "components"));
        selected = rootPath;
    }
    @Override public EditSession session() { return session; }
    public List<Object> rootPath() { return rootPath; }
    public EditorPage page() { return page; }

    @Override protected void init() {
        w = Math.min(760, width - 8); h = Math.min(490, height - 8); x = (width - w) / 2; y = (height - h) / 2;
        editingButtons.clear(); syncButton = null;
        int columns = w >= 400 ? 8 : 4;
        String[] shortTabs = {"物品堆", "显示信息", "附魔", "属性", "使用行为", "内部物品", "自定义", "原始数据"};
        for (EditorPage candidate : EditorPage.values()) {
            int i = candidate.ordinal(), bw = (w - 16) / columns;
            var b = button(columns == 8 ? shortTabs[i] : candidate.title, x + 8 + i % columns * bw, y + 34 + i / columns * 21, bw - 2, () -> {
                page = candidate; selected = rootPath; scroll = horizontal = 0; rebuildWidgets();
            }); b.active = candidate != page;
            b.setTooltip(Tooltip.create(Component.literal(candidate.title + "：" + candidate.hint)));
        }
        int searchY = y + (columns == 8 ? 58 : 79);
        search = addRenderableWidget(new EditBox(font, x + 8, searchY, w - 194, 18, Component.literal("搜索数据")));
        search.setMaxLength(2048); search.setHint(Component.literal("搜索节点名、类型或值")); search.setValue(query);
        search.setResponder(s -> { query = s; scroll = 0; rebuildRows(); });
        button("简单模式", x + w - 178, searchY - 1, 54, () -> minecraft.setScreen(new SimpleComponentScreen(session, this)));
        button("展开", x + w - 120, searchY - 1, 54, () -> { expandAll(rootPath, 0); rebuildRows(); });
        button("折叠", x + w - 62, searchY - 1, 54, () -> { expanded.clear(); expanded.add(rootPath); rebuildRows(); });
        treeTop = searchY + 22; treeBottom = y + h - 65;
        String[] operations = {"编辑", "新增", "删除", "重命名", "内部物品", "复制节点", "SNBT", parent == null ? "简单模式" : "返回上层"};
        Runnable[] actions = {this::editSelected, this::addSelected, this::deleteSelected, this::renameSelected, this::openNested, this::copySelected,
                () -> minecraft.setScreen(new SnbtScreen()), parent == null ? () -> minecraft.setScreen(new SimpleComponentScreen(session, this)) : () -> minecraft.setScreen(parent)};
        int aw = (w - 16) / operations.length;
        for (int i = 0; i < operations.length; i++) {
            Button b = button(operations[i], x + 8 + i * aw, y + h - 60, aw - 2, actions[i]);
            if (i < 4) { b.active = session.editable && !session.syncing; editingButtons.add(b); }
        }
        if (session.editable) syncButton = button("同步", x + w - 145, y + 8, 66, this::synchronize);
        button("关闭", x + w - 75, y + 8, 67, this::onClose);
        rebuildRows();
    }

    private Button button(String text, int bx, int by, int bw, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(bx, by, bw, 20).build());
    }
    public CompoundTag currentRoot() {
        Tag tag = StackData.at(session.document, rootPath);
        return tag instanceof CompoundTag c ? c : new CompoundTag();
    }
    private void setRoot(CompoundTag data) { session.edit(StackData.replace(session.document, rootPath, data)); rebuildRows(); }
    private void replace(List<Object> path, Tag value) { session.edit(StackData.replace(session.document, path, value)); rebuildRows(); }

    private void rebuildRows() {
        rows.clear(); CompoundTag root = currentRoot();
        if (page == EditorPage.RAW) append(rootPath, "ItemStack", root, 0);
        else if (page == EditorPage.STACK) {
            append(rootPath, "ItemStack", root, 0, false);
            root.keySet().stream().sorted().filter(k -> !k.equals("components")).forEach(k -> append(StackData.child(rootPath, k), k, root.get(k), 1));
        } else {
            var componentsPath = StackData.child(rootPath, "components");
            var components = root.getCompoundOrEmpty("components");
            append(componentsPath, "components", components, 0, false);
            components.keySet().stream().sorted().filter(page::accepts).forEach(k -> append(StackData.child(componentsPath, k), k, components.get(k), 1));
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * ROW - (treeBottom - treeTop))));
        seenRevision = session.revision;
        if (!rootPath.isEmpty()) {
            try { nestedPreview = StackData.decode(root, minecraft.level.registryAccess()); }
            catch (RuntimeException ex) { nestedPreview = ItemStack.EMPTY; }
        }
    }
    private void append(List<Object> path, String name, Tag value, int depth) { append(path, name, value, depth, true); }
    private void append(List<Object> path, String name, Tag value, int depth, boolean recurse) {
        if (value == null || depth > 48 || rows.size() >= 10000) return;
        boolean branch = value instanceof CompoundTag || value instanceof CollectionTag;
        String needle = query.toLowerCase(Locale.ROOT);
        boolean matches = needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)
                || StackData.type(value).toLowerCase(Locale.ROOT).contains(needle)
                || (!branch && value.toString().toLowerCase(Locale.ROOT).contains(needle));
        if (matches) rows.add(new Row(path, name, value, depth, branch));
        if (!recurse || (!expanded.contains(path) && needle.isEmpty())) return;
        if (value instanceof CompoundTag c) c.keySet().stream().sorted().forEach(k -> append(StackData.child(path, k), k, c.get(k), depth + 1));
        else if (value instanceof CollectionTag c) for (int i = 0; i < c.size() && rows.size() < 10000; i++) append(StackData.child(path, i), "[" + i + "]", c.get(i), depth + 1);
    }
    private void expandAll(List<Object> path, int depth) {
        if (depth > 32 || expanded.size() > 10000) return;
        expanded.add(path); Tag value = StackData.at(session.document, path);
        if (value instanceof CompoundTag c) for (String key : c.keySet()) expandAll(StackData.child(path, key), depth + 1);
        else if (value instanceof CollectionTag c) for (int i = 0; i < c.size(); i++) expandAll(StackData.child(path, i), depth + 1);
    }

    private void editSelected() {
        Tag value = StackData.at(session.document, selected);
        if (value == null) return;
        List<Object> path = selected;
        minecraft.setScreen(new ValueScreen(this, "编辑节点 / " + StackData.type(value), path.toString(), value.toString(), false, true,
                session.editable && !session.syncing, (key, text) -> replace(path, parse(text))));
    }
    private static Tag parse(String text) {
        try { return TagParser.create(NbtOps.INSTANCE).parseFully(text); }
        catch (Exception ex) { throw new IllegalArgumentException(ex.getMessage()); }
    }
    private void addSelected() {
        if (!session.editable || session.syncing) return;
        Tag value = StackData.at(session.document, selected);
        List<Object> destination = selected;
        if (!(value instanceof CompoundTag) && !(value instanceof CollectionTag)) {
            destination = selected.isEmpty() ? rootPath : selected.subList(0, selected.size() - 1);
            value = StackData.at(session.document, destination);
        }
        if (value == null && destination.equals(StackData.child(rootPath, "components"))) value = new CompoundTag();
        final List<Object> path = List.copyOf(destination); final Tag target = value;
        if (!(target instanceof CompoundTag) && !(target instanceof CollectionTag)) { session.status = "请先选择 Compound、List 或数组"; return; }
        minecraft.setScreen(new ValueScreen(this, "新增数据节点", target instanceof CompoundTag ? "new_key" : "追加到列表末尾", "{}",
                target instanceof CompoundTag, true, true, (key, text) -> {
            Tag copy = target.copy(); Tag added = parse(text);
            if (copy instanceof CompoundTag c) {
                if (c.contains(key)) throw new IllegalArgumentException("该键已存在，请使用编辑");
                c.remove("!" + key); c.put(key, added);
            } else if (copy instanceof CollectionTag c && !c.addTag(c.size(), added)) throw new IllegalArgumentException("数组元素类型不匹配");
            replace(path, copy); expanded.add(path);
        }));
    }
    private void deleteSelected() {
        if (!session.editable || session.syncing) return;
        try {
            session.edit(selected.equals(rootPath) ? StackData.replace(session.document, rootPath, new CompoundTag()) : StackData.remove(session.document, selected));
            selected = rootPath; rebuildRows();
        }
        catch (RuntimeException ex) { session.status = ex.getMessage(); }
    }
    private void renameSelected() {
        if (!session.editable || session.syncing || selected.isEmpty() || !(selected.getLast() instanceof String old)) return;
        List<Object> path = selected, parentPath = path.subList(0, path.size() - 1);
        Tag value = StackData.at(session.document, path);
        if (value == null) return;
        minecraft.setScreen(new ValueScreen(this, "重命名数据键", old, value.toString(), true, true, true, (key, text) -> {
            CompoundTag copy = ((CompoundTag) StackData.at(session.document, parentPath)).copy();
            if (!key.equals(old) && copy.contains(key)) throw new IllegalArgumentException("目标键已存在");
            copy.remove(old); copy.remove("!" + key); copy.put(key, parse(text)); replace(parentPath, copy);
            selected = StackData.child(parentPath, key);
        }));
    }
    private void openNested() {
        List<Object> path = selected; Tag value = StackData.at(session.document, path);
        if (value instanceof CompoundTag c && c.get("item") instanceof CompoundTag item) { value = item; path = StackData.child(path, "item"); }
        if (value instanceof CompoundTag c && c.contains("id")) minecraft.setScreen(new ItemEditorScreen(session, this, path));
        else session.status = "请选择内部物品（含 id 的 Compound，或容器中带 item 的条目）";
    }
    private void copySelected() { copy(StackData.at(session.document, selected)); }
    private void copy(Tag data) { if (data != null) { minecraft.keyboardHandler.setClipboard(data.toString()); session.status = "已复制 SNBT"; } }
    private void paste() {
        if (!session.editable || session.syncing) return;
        minecraft.setScreen(new ValueScreen(this, "从剪贴板导入 SNBT", "完整 ItemStack", minecraft.keyboardHandler.getClipboard(), false, false, true,
                (key, text) -> loadSnbt(text)));
    }
    private void loadSnbt(String text) {
        Tag tag = parse(text);
        if (!(tag instanceof CompoundTag c)) throw new IllegalArgumentException("完整物品必须为 Compound");
        // Parsing failure leaves both document and dialog intact. Codec errors remain inspectable in the tree.
        setRoot(c);
    }
    private Path exportDirectory() { return minecraft.gameDirectory.toPath().resolve("nbt-maker").resolve("exports"); }
    private void importFile() {
        if (!session.editable || session.syncing) return;
        minecraft.setScreen(new ValueScreen(this, "导入本地 SNBT 文件", "填写绝对路径，或相对于游戏目录的路径", "nbt-maker/exports/item.snbt", false, false, true,
                (key, text) -> {
                    try { Path file = Path.of(text.trim()); if (!file.isAbsolute()) file = minecraft.gameDirectory.toPath().resolve(file); loadSnbt(Files.readString(file, StandardCharsets.UTF_8)); }
                    catch (java.io.IOException ex) { throw new IllegalArgumentException("读取失败：" + ex.getMessage()); }
                }));
    }
    private void exportFile() {
        try {
            Files.createDirectories(exportDirectory());
            Path file = exportDirectory().resolve("item-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".snbt");
            Files.writeString(file, currentRoot().toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            session.status = "已导出：" + file;
        } catch (Exception ex) { session.status = "导出失败：" + ex.getMessage(); }
    }

    private final class SnbtScreen extends Screen implements EditorLayer {
        private int sx, sy, sw, sh;
        SnbtScreen() { super(Component.literal("SNBT 导入与导出")); }
        @Override public EditSession session() { return session; }
        @Override protected void init() {
            sw = Math.min(320, width - 16); sh = Math.min(214, height - 16); sx = (width - sw) / 2; sy = (height - sh) / 2;
            String[] labels = {"复制副本", "复制原物品", "粘贴 SNBT", "导入文件", "导出文件", "返回"};
            Runnable[] tasks = {() -> copy(currentRoot()), () -> copy(StackData.at(session.observed, rootPath)), ItemEditorScreen.this::paste,
                    ItemEditorScreen.this::importFile, ItemEditorScreen.this::exportFile, () -> {}};
            for (int i = 0; i < labels.length; i++) {
                Runnable task = tasks[i];
                Button b = addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> {
                    minecraft.setScreen(ItemEditorScreen.this); task.run();
                }).bounds(sx + 8, sy + 28 + i * 26, sw - 16, 20).build());
                if (i == 2 || i == 3) b.active = session.editable && !session.syncing;
            }
        }
        @Override public void onClose() { session.close(); }
        @Override public boolean keyPressed(KeyEvent key) {
            if (minecraft.options.keyInventory.matches(key)) { onClose(); return true; }
            return super.keyPressed(key);
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics g, int mx, int my, float delta) {
            panel(g, sx, sy, sw, sh); g.drawString(font, title, sx + 8, sy + 10, 0xFF303030, false);
            super.render(g, mx, my, delta);
        }
    }

    public void addComponent(String name, String snbt) {
        CompoundTag root = currentRoot().copy(), components = root.getCompoundOrEmpty("components").copy();
        if (components.contains(name)) { session.status = "组件已存在，请展开后编辑"; return; }
        components.remove("!" + name); components.put(name, parse(snbt)); root.put("components", components); setRoot(root);
        expanded.add(StackData.child(rootPath, "components"));
        expanded.add(StackData.child(StackData.child(rootPath, "components"), name));
    }


    public void synchronize() {
        if (!session.editable || session.syncing) return;
        if (StackData.risky(session.document)) minecraft.setScreen(new RiskScreen(this));
        else session.sync(success -> rebuildRows());
    }
    private static final class RiskScreen extends ConfirmScreen implements EditorLayer {
        private final ItemEditorScreen parent;
        RiskScreen(ItemEditorScreen parent) {
            super(answer -> { parent.minecraft.setScreen(parent); if (answer) parent.session.sync(success -> parent.rebuildRows()); },
                    Component.literal("高风险数据"), Component.literal("当前数据可能导致游戏异常或崩溃，是否继续同步？"),
                    Component.literal("确定同步"), Component.literal("取消"));
            this.parent = parent;
        }
        @Override public EditSession session() { return parent.session; }
        @Override public boolean isPauseScreen() { return false; }
    }

    @Override public void tick() {
        if (seenRevision != session.revision) rebuildRows();
        for (Button button : editingButtons) button.active = session.editable && !session.syncing;
        if (syncButton != null) syncButton.active = !session.syncing;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || (minecraft.options.keyInventory.matches(event) && !search.isFocused())) { onClose(); return true; }
        if (!search.isFocused()) {
            if (event.isCopy()) { copySelected(); return true; }
            if (event.isConfirmation()) { editSelected(); return true; }
            if (event.key() == 261 && session.editable) { deleteSelected(); return true; }
            if (event.isLeft() || event.isRight()) { horizontal = Math.max(0, horizontal + (event.isLeft() ? -30 : 30)); return true; }
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() >= x + w - 14 && event.x() < x + w - 7 && event.y() >= treeTop && event.y() < treeBottom) {
            draggingBar = true; dragBar(event.y()); return true;
        }
        if (event.x() >= x + 8 && event.x() < x + w - 8 && event.y() >= treeTop && event.y() < treeBottom) {
            int i = ((int) event.y() - treeTop + scroll) / ROW;
            if (i < rows.size()) {
                Row row = rows.get(i); selected = row.path;
                setFocused(null);
                int arrowX = x + 12 + row.depth * 12 - horizontal;
                if (row.branch && event.x() < arrowX + 12) { if (!expanded.remove(row.path)) expanded.add(row.path); rebuildRows(); }
                else if (doubleClick || event.button() == 1) editSelected();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
    private void dragBar(double my) {
        int maximum = Math.max(0, rows.size() * ROW - (treeBottom - treeTop));
        scroll = Math.max(0, Math.min(maximum, (int) ((my - treeTop) / (treeBottom - treeTop) * maximum)));
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingBar) { dragBar(event.y()); return true; }
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        draggingBar = false; return super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (my >= treeTop && my < treeBottom) {
            scroll = Math.max(0, Math.min(Math.max(0, rows.size() * ROW - (treeBottom - treeTop)), scroll - (int) (vy * ROW * 3)));
            horizontal = Math.max(0, horizontal - (int) (hx * 24)); return true;
        }
        return super.mouseScrolled(mx, my, hx, vy);
    }
    @Override public void onClose() { session.close(); }
    @Override public boolean isPauseScreen() { return false; }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF373737);
        g.fill(x + 1, y + 1, x + w - 2, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        panel(g, x, y, w, h);
        g.blit(RenderPipelines.GUI_TEXTURED, CHEST, x + 8, y + 8, 7, 17, 18, 18, 256, 256);
        ItemStack preview = rootPath.isEmpty() ? session.preview : nestedPreview;
        String name = "空物品";
        try {
            if (!preview.isEmpty()) { g.renderItem(preview, x + 9, y + 9); g.renderItemDecorations(font, preview, x + 9, y + 9); name = preview.getHoverName().getString(); }
        } catch (RuntimeException ex) { name = "预览失败"; }
        g.drawString(font, font.plainSubstrByWidth(name, w - 185), x + 32, y + 6, 0xFF303030, false);
        g.drawString(font, font.plainSubstrByWidth(session.editable ? (rootPath.isEmpty() ? "本地 · 可编辑" : "内部物品 · 同步写回整个物品") : "多人 · 严格只读", w - 185), x + 32, y + 20, 0xFF555555, false);
        g.fill(x + 8, treeTop, x + w - 8, treeBottom, 0xFF8B8B8B);
        g.enableScissor(x + 8, treeTop, x + w - 12, treeBottom);
        for (int i = scroll / ROW; i < rows.size() && treeTop + i * ROW - scroll < treeBottom; i++) {
            Row row = rows.get(i); int ry = treeTop + i * ROW - scroll;
            if (row.path.equals(selected)) g.fill(x + 9, ry, x + w - 12, ry + ROW, 0xFF5C6786);
            String label = (row.branch ? expanded.contains(row.path) ? "− " : "+ " : "  ") + row.name + "  [" + StackData.type(row.value) + "]  " + StackData.summary(row.value);
            g.drawString(font, label, x + 12 + row.depth * 12 - horizontal, ry + 3, 0xFFFFFFFF, true);
        }
        g.disableScissor();
        int contentHeight = treeBottom - treeTop;
        if (rows.size() * ROW > contentHeight) {
            int bar = Math.max(8, contentHeight * contentHeight / (rows.size() * ROW));
            int by = treeTop + (contentHeight - bar) * scroll / (rows.size() * ROW - contentHeight);
            g.fill(x + w - 12, treeTop, x + w - 8, treeBottom, 0xFF444444);
            g.fill(x + w - 12, by, x + w - 8, by + bar, 0xFFCCCCCC);
        }
        String warning = !session.valid ? "目标位置已失效；仍可保留副本并尝试同步" : session.changed ? "警告：原物品已发生变化，同步将覆盖目标位置" : session.location;
        String feedback = !session.error.isEmpty() ? "数据无法生成物品：" + session.error : session.status.isEmpty() ? page.hint : session.status;
        g.drawString(font, font.plainSubstrByWidth(warning, w - 16), x + 8, y + h - 33, session.changed || !session.valid ? 0xFFAA2222 : 0xFF555555, false);
        g.drawString(font, font.plainSubstrByWidth(feedback, w - 16), x + 8, y + h - 18, !session.error.isEmpty() || feedback.startsWith("同步失败") ? 0xFFAA2222 : 0xFF303030, false);
        super.render(g, mx, my, delta);
        if (mx >= x + 8 && mx < x + 28 && my >= y + 8 && my < y + 28 && !preview.isEmpty()) {
            try { g.setTooltipForNextFrame(font, preview, mx, my); } catch (RuntimeException ignored) { }
        }
        if (my >= y + h - 36 && my < y + h - 6) g.setTooltipForNextFrame(font, Component.literal(my < y + h - 23 ? warning : feedback), mx, my);
    }
    private record Row(List<Object> path, String name, Tag value, int depth, boolean branch) {}
}
