package com.sinkie114.nbtmaker;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Local SNBT favourites under the game directory. Loading only replaces the edit copy; sync still writes the item. */
final class LibraryScreen extends Screen implements EditorLayer {
    private static final int ROW = 24, MAX_ENTRIES = 1000;
    private record Entry(Path file, String name, CompoundTag data, ItemStack stack, String problem) {}
    private final Screen parent;
    private final EditSession session;
    private final List<Object> path;
    private final List<Entry> entries = new ArrayList<>();
    private String nameText, message = "";
    private boolean messageError;
    private int x, y, w, h, offset, pageSize;

    LibraryScreen(Screen parent, EditSession session, List<Object> path) {
        super(Component.literal("物品收藏夹"));
        this.parent = parent; this.session = session; this.path = List.copyOf(path);
        nameText = sanitize(current().isEmpty() ? "" : preview(current()).getHoverName().getString());
    }
    @Override public EditSession session() { return session; }

    static Path directory(net.minecraft.client.Minecraft client) { return client.gameDirectory.toPath().resolve("nbt-maker").resolve("library"); }
    private CompoundTag current() { return StackData.at(session.document, path) instanceof CompoundTag c ? c : new CompoundTag(); }
    private ItemStack preview(CompoundTag data) {
        try { return StackData.decode(data, session.client.level.registryAccess()); } catch (RuntimeException ex) { return ItemStack.EMPTY; }
    }
    private boolean writable() { return session.editable && !session.syncing; }

    @Override protected void init() {
        w = Math.min(560, width - 12); h = Math.min(400, height - 12); x = (width - w) / 2; y = (height - h) / 2;
        reload();
        EditBox name = addRenderableWidget(new EditBox(font, x + 8, y + 28, w - 124, 20, Component.literal("收藏名称")));
        name.setMaxLength(64); name.setValue(nameText); name.setHint(Component.literal("收藏名称（作为文件名）"));
        name.setResponder(value -> nameText = value);
        addRenderableWidget(Button.builder(Component.literal("保存当前物品"), b -> save()).bounds(x + w - 112, y + 28, 104, 20)
                .tooltip(Tooltip.create(Component.literal("把当前副本（含未同步修改）保存为 SNBT 文件"))).build()).active = !current().isEmpty();
        int top = y + 56;
        pageSize = Math.max(1, (h - 120) / ROW);
        offset = Math.max(0, Math.min(offset, Math.max(0, (entries.size() - 1) / pageSize * pageSize)));
        for (int i = offset; i < Math.min(entries.size(), offset + pageSize); i++) {
            Entry entry = entries.get(i); int ry = top + (i - offset) * ROW;
            String label = entry.problem().isEmpty() ? entry.name() : entry.name() + "（无法载入）";
            Button load = addRenderableWidget(Button.builder(Component.literal(label), b -> load(entry))
                    .bounds(x + 30, ry, w - 104, 20)
                    .tooltip(Tooltip.create(Component.literal(entry.problem().isEmpty() ? "载入到编辑副本：" + entry.file().getFileName() : entry.problem()))).build());
            load.active = writable() && entry.problem().isEmpty();
            addRenderableWidget(Button.builder(Component.literal("删除"), b -> delete(entry)).bounds(x + w - 70, ry, 62, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { offset -= pageSize; rebuildWidgets(); }).bounds(x + 8, y + h - 28, 56, 20).build()).active = offset > 0;
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { offset += pageSize; rebuildWidgets(); }).bounds(x + 68, y + h - 28, 56, 20).build()).active = offset + pageSize < entries.size();
        addRenderableWidget(Button.builder(Component.literal("复制目录路径"), b -> copyFolder()).bounds(x + 128, y + h - 28, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(x + w - 70, y + h - 28, 62, 20).build());
        setInitialFocus(name);
    }

    private void reload() {
        entries.clear();
        Path dir = directory(minecraft);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(f -> f.getFileName().toString().endsWith(".snbt") && Files.isRegularFile(f))
                    .sorted(Comparator.comparing(LibraryScreen::modified).reversed()).limit(MAX_ENTRIES).toList();
            for (Path file : sorted) entries.add(read(file));
        } catch (IOException ex) { show("读取收藏夹失败：" + ex.getMessage(), true); }
    }
    private static long modified(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); } catch (IOException ex) { return 0; }
    }
    private Entry read(Path file) {
        String fileName = file.getFileName().toString(), name = fileName.substring(0, fileName.length() - ".snbt".length());
        try {
            CompoundTag data = TagParser.parseCompoundFully(Files.readString(file, StandardCharsets.UTF_8));
            ItemStack stack = StackData.decode(data, minecraft.level.registryAccess());
            return new Entry(file, name, data, stack, "");
        } catch (Exception ex) {
            return new Entry(file, name, null, ItemStack.EMPTY, "无法解析：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
    }

    static String sanitize(String raw) {
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64).trim();
        return cleaned;
    }
    private void save() {
        CompoundTag data = current();
        if (data.isEmpty()) { show("空物品无法收藏", true); return; }
        String base = sanitize(nameText);
        if (base.isEmpty()) base = "item";
        try {
            Path dir = directory(minecraft); Files.createDirectories(dir);
            Path file = dir.resolve(base + ".snbt");
            for (int n = 2; Files.exists(file); n++) file = dir.resolve(base + " (" + n + ").snbt");
            Files.writeString(file, data.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            show("已保存：" + file.getFileName(), false);
        } catch (IOException | RuntimeException ex) { show("保存失败：" + ex.getMessage(), true); }
        rebuildWidgets();
    }
    private void load(Entry entry) {
        if (!writable() || entry.data() == null) return;
        Tag existing = StackData.at(session.document, path);
        if (!path.isEmpty() && !(existing instanceof CompoundTag)) { show("内部物品位置已失效", true); return; }
        session.edit(StackData.replace(session.document, path, entry.data()));
        session.status = "已载入收藏：" + entry.name() + "（可 Ctrl+Z 撤销，点击“同步”写回）";
        minecraft.setScreen(parent);
    }
    private void delete(Entry entry) {
        minecraft.setScreen(new DeleteConfirm(this, entry));
    }
    /** Keeps the session alive while confirming, like the sync risk dialog. */
    private static final class DeleteConfirm extends net.minecraft.client.gui.screens.ConfirmScreen implements EditorLayer {
        private final LibraryScreen owner;
        DeleteConfirm(LibraryScreen owner, Entry entry) {
            super(ok -> {
                if (ok) {
                    try { Files.deleteIfExists(entry.file()); owner.show("已删除：" + entry.name(), false); }
                    catch (IOException ex) { owner.show("删除失败：" + ex.getMessage(), true); }
                }
                owner.minecraft.setScreen(owner);
            }, Component.literal("删除收藏“" + entry.name() + "”？"), Component.literal("将删除文件 " + entry.file()),
                    Component.literal("删除"), Component.literal("取消"));
            this.owner = owner;
        }
        @Override public EditSession session() { return owner.session; }
        @Override public boolean isPauseScreen() { return false; }
    }
    private void copyFolder() {
        try { Files.createDirectories(directory(minecraft)); }
        catch (IOException ex) { show("无法创建收藏夹目录：" + ex.getMessage(), true); return; }
        minecraft.keyboardHandler.setClipboard(directory(minecraft).toAbsolutePath().toString());
        show("已复制目录路径：" + directory(minecraft).toAbsolutePath(), false);
    }
    private void show(String text, boolean error) { message = text; messageError = error; }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(KeyEvent key) {
        if (key.isEscape()) { onClose(); return true; }
        return super.keyPressed(key);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        ItemEditorScreen.panel(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 10, 0xFF303030, false);
        int top = y + 56;
        if (entries.isEmpty()) g.drawString(font, "收藏夹为空。输入名称后点击“保存当前物品”。", x + 8, top + 6, 0xFF555555, false);
        for (int i = offset; i < Math.min(entries.size(), offset + pageSize); i++) {
            int ry = top + (i - offset) * ROW;
            g.fill(x + 7, ry, x + 27, ry + 20, 0xFF8B8B8B);
            ItemStack stack = entries.get(i).stack();
            if (!stack.isEmpty()) {
                try { g.renderItem(stack, x + 9, ry + 2); g.renderItemDecorations(font, stack, x + 9, ry + 2); } catch (RuntimeException ignored) { }
            }
        }
        String feedback = !message.isEmpty() ? message : session.editable ? "单击收藏载入到副本；文件位于游戏目录 nbt-maker/library。"
                : "多人 · 只读：可以保存收藏，但不能载入到副本。";
        g.drawString(font, font.plainSubstrByWidth(feedback, w - 16), x + 8, y + h - 44, messageError ? 0xFFAA2222 : 0xFF555555, false);
        super.render(g, mx, my, delta);
        for (int i = offset; i < Math.min(entries.size(), offset + pageSize); i++) {
            int ry = top + (i - offset) * ROW;
            ItemStack stack = entries.get(i).stack();
            if (!stack.isEmpty() && mx >= x + 7 && mx < x + 27 && my >= ry && my < ry + 20) {
                try { g.setTooltipForNextFrame(font, stack, mx, my); } catch (RuntimeException ignored) { }
            }
        }
    }
}
