package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;

/** 1.0.5：简单模式的显示信息表单与容器格子界面。 */
final class DisplayContainerTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static EditSession session(Minecraft c) { return ((EditorLayer) c.screen).session(); }
    private static CompoundTag components(Minecraft c) { return session(c).document.getCompoundOrEmpty("components"); }

    static void exercise(ClientGameTestContext ctx, TestServerContext server) {
        CompoundTag baseline = ctx.computeOnClient(c -> session(c).document.copy());
        clickIfActive(ctx, "显示信息");

        // ---- 自定义名称：文字 + 颜色 + 样式 ----
        clickPage(ctx, "自定义名称（支持文本样式）");
        ctx.runOnClient(c -> check(c.screen instanceof EditorFormScreen, "Custom name opens the text form"));
        input(ctx, "文字", "测试名称");
        clickPageContains(ctx, "默认颜色");
        search(ctx, "gold", "搜索选项"); clickContains(ctx, " · gold");
        ctx.runOnClient(c -> ((EditorFormScreen) c.screen).draft.putBoolean("bold", true));
        ctx.takeScreenshot("nbt-display-name-form");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> {
            CompoundTag name = components(c).getCompoundOrEmpty("minecraft:custom_name");
            check(name.getStringOr("text", "").equals("测试名称"), "Name text saved: " + name);
            check(name.getStringOr("color", "").equals("gold"), "Named color saved: " + name);
            check(name.getBooleanOr("bold", false), "Bold saved");
            check(name.contains("italic") && !name.getBooleanOr("italic", true), "Custom name is explicitly non-italic");
            check(session(c).preview.getHoverName().getString().equals("测试名称"), "Preview uses new name");
        });
        clickPage(ctx, "自定义名称（支持文本样式）");
        inputPage(ctx, "自定义十六进制颜色（留空则用上面的颜色）", "#12ab34");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(components(c).getCompoundOrEmpty("minecraft:custom_name").getStringOr("color", "").equals("#12AB34"), "Hex color overrides"));
        clickPage(ctx, "自定义名称（支持文本样式）");
        inputPage(ctx, "自定义十六进制颜色（留空则用上面的颜色）", "not-a-color");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(c.screen instanceof EditorFormScreen, "Invalid hex keeps the form open"));
        click(ctx, "取消");

        // ---- Lore：增行、排序、删除 ----
        clickPage(ctx, "Lore 描述");
        ctx.runOnClient(c -> check(c.screen instanceof LoreScreen, "Lore opens line editor"));
        click(ctx, "新增一行"); input(ctx, "文字", "第一行"); click(ctx, "保存到副本");
        click(ctx, "新增一行"); input(ctx, "文字", "第二行"); click(ctx, "保存到副本");
        click(ctx, "新增一行"); input(ctx, "文字", "要删掉的行"); click(ctx, "保存到副本");
        clickNth(ctx, "删除", 2);
        clickNth(ctx, "↑", 1);
        ctx.takeScreenshot("nbt-display-lore");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> {
            ListTag lore = components(c).getListOrEmpty("minecraft:lore");
            check(lore.size() == 2, "Two lore lines: " + lore);
            check(lore.getCompoundOrEmpty(0).getStringOr("text", "").equals("第二行"), "Move up reorders lines: " + lore);
            check(lore.getCompoundOrEmpty(1).getStringOr("text", "").equals("第一行"), "Second line kept");
            check(lore.getCompoundOrEmpty(0).getStringOr("color", "").equals("gray"), "New lines default to gray");
            check(session(c).error.isEmpty(), "Lore decodes: " + session(c).error);
        });
        // Lore 取消不改副本
        CompoundTag beforeCancel = ctx.computeOnClient(c -> session(c).document.copy());
        clickPage(ctx, "Lore 描述"); click(ctx, "清空"); click(ctx, "取消");
        ctx.runOnClient(c -> check(session(c).document.equals(beforeCancel), "Cancelled lore edits are discarded"));

        // ---- 稀有度 ----
        clickPage(ctx, "稀有度");
        clickPageContains(ctx, "普通（白色名称）");
        search(ctx, "epic", "搜索选项"); clickContains(ctx, "史诗");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(session(c).preview.get(DataComponents.RARITY) == Rarity.EPIC, "Rarity saved"));

        // ---- 染色 ----
        clickPage(ctx, "染色颜色（RGB 整数）");
        inputPage(ctx, "或填写十六进制颜色（留空则用上面的选择）", "#FF0000");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(components(c).getIntOr("minecraft:dyed_color", 0) == 0xFF0000, "Dye color saved"));

        // ---- 提示框：隐藏附魔行 ----
        clickPage(ctx, "提示显示/隐藏组件");
        clickPageContains(ctx, "选择要隐藏的组件");
        search(ctx, "minecraft:enchantments", "搜索"); clickContains(ctx, "附魔 · minecraft:enchantments");
        click(ctx, "完成"); click(ctx, "保存到副本");
        ctx.runOnClient(c -> {
            var hidden = components(c).getCompoundOrEmpty("minecraft:tooltip_display").getListOrEmpty("hidden_components");
            check(hidden.size() == 1 && hidden.getStringOr(0, "").equals("minecraft:enchantments"), "Hidden components saved: " + hidden);
            check(!session(c).preview.get(DataComponents.TOOLTIP_DISPLAY).shows(DataComponents.ENCHANTMENTS), "Enchantments hidden in tooltip");
        });

        // ---- 附魔光效 ----
        clickPage(ctx, "附魔光效");
        clickPageContains(ctx, "强制发光");
        clickContains(ctx, "强制不发光");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(Boolean.FALSE.equals(session(c).preview.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)), "Glint override false"));

        // ---- 物品模型 ----
        clickPage(ctx, "物品模型");
        inputPage(ctx, "或填写模型 ID（资源包模型，留空则用上面的选择）", "minecraft:apple");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(components(c).getStringOr("minecraft:item_model", "").equals("minecraft:apple"), "Item model saved"));

        // ---- 自定义模型数据 ----
        clickPage(ctx, "自定义模型数据");
        inputPage(ctx, "floats（逗号分隔的小数）", "1, 2.5");
        inputPage(ctx, "strings（逗号分隔的文字）", "ruby，glow");
        inputPage(ctx, "flags（逗号分隔的 true/false）", "true");
        inputPage(ctx, "colors（逗号分隔的十六进制颜色）", "#FF0000");
        click(ctx, "保存到副本");
        ctx.runOnClient(c -> {
            var cmd = session(c).preview.get(DataComponents.CUSTOM_MODEL_DATA);
            check(cmd != null && cmd.floats().size() == 2 && cmd.floats().get(1) == 2.5f, "Model floats: " + cmd);
            check(cmd.strings().equals(java.util.List.of("ruby", "glow")), "Model strings (full-width comma): " + cmd);
            check(cmd.flags().equals(java.util.List.of(true)) && cmd.colors().equals(java.util.List.of(0xFF0000)), "Model flags/colors: " + cmd);
        });

        // ---- 容器格子 ----
        clickIfActive(ctx, "容器与物品");
        clickPage(ctx, "容器内物品");
        ctx.runOnClient(c -> check(c.screen instanceof ItemGridScreen, "Container opens the slot grid"));
        clickSlot(ctx, 3, false);
        click(ctx, "放入物品");
        search(ctx, "minecraft:diamond", "搜索选项"); clickContains(ctx, " · minecraft:diamond");
        ctx.runOnClient(c -> {
            ListTag list = components(c).getListOrEmpty("minecraft:container");
            check(list.size() == 1 && list.getCompoundOrEmpty(0).getIntOr("slot", -1) == 3, "Item placed in slot 3: " + list);
            check(c.screen instanceof ItemGridScreen, "Returns to grid after picking");
        });
        click(ctx, "数量"); input(ctx, "数量（内部物品不能为 0）", "5"); click(ctx, "保存到副本");
        ctx.runOnClient(c -> check(components(c).getListOrEmpty("minecraft:container").getCompoundOrEmpty(0).getCompoundOrEmpty("item").getIntOr("count", 0) == 5, "Inner count"));
        ctx.takeScreenshot("nbt-container-grid");
        // 进入内部物品的简单模式，给它改名
        click(ctx, "编辑");
        ctx.runOnClient(c -> check(c.screen instanceof SimpleComponentScreen s && !s.path().isEmpty(), "Inner item opens nested simple mode"));
        clickPage(ctx, "自定义名称（支持文本样式）");
        input(ctx, "文字", "内部钻石"); click(ctx, "保存到副本");
        ctx.runOnClient(c -> {
            CompoundTag inner = components(c).getListOrEmpty("minecraft:container").getCompoundOrEmpty(0).getCompoundOrEmpty("item");
            check(inner.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:custom_name").getStringOr("text", "").equals("内部钻石"), "Nested rename lands in the container entry: " + inner);
            check(session(c).preview.is(Items.DIAMOND_SWORD), "Outer item unchanged");
        });
        ctx.takeScreenshot("nbt-container-inner");
        click(ctx, "返回上层");
        ctx.runOnClient(c -> check(c.screen instanceof ItemGridScreen, "Back returns to grid"));
        // 双击空格直接放物品，再删除
        clickSlot(ctx, 5, true);
        search(ctx, "minecraft:stone", "搜索选项"); clickContains(ctx, " · minecraft:stone");
        ctx.runOnClient(c -> check(components(c).getListOrEmpty("minecraft:container").size() == 2, "Double-click empty slot adds item"));
        clickSlot(ctx, 5, false); click(ctx, "删除");
        ctx.runOnClient(c -> check(components(c).getListOrEmpty("minecraft:container").size() == 1, "Delete removes the slot entry"));
        click(ctx, "返回");

        // 收纳袋：有序列表，末尾空格追加
        clickPage(ctx, "束口袋内容");
        clickSlot(ctx, 0, true);
        search(ctx, "minecraft:arrow", "搜索选项"); clickContains(ctx, " · minecraft:arrow");
        ctx.runOnClient(c -> {
            ListTag bundle = components(c).getListOrEmpty("minecraft:bundle_contents");
            check(bundle.size() == 1 && bundle.getCompoundOrEmpty(0).getStringOr("id", "").equals("minecraft:arrow"), "Bundle append: " + bundle);
        });
        click(ctx, "返回");

        // ---- 同步并在服务端核对 ----
        click(ctx, "同步"); ctx.waitFor(c -> session(c).status.equals("同步成功"));
        server.runOnServer(s -> {
            var item = s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0);
            check(item.get(DataComponents.RARITY) == Rarity.EPIC, "Server rarity");
            check(item.get(DataComponents.LORE).lines().size() == 2, "Server lore");
            var inner = item.get(DataComponents.CONTAINER).stream().toList().get(3);
            check(inner.is(Items.DIAMOND) && inner.getCount() == 5 && inner.getHoverName().getString().equals("内部钻石"), "Server nested item: " + inner);
            check(item.get(DataComponents.BUNDLE_CONTENTS).size() == 1, "Server bundle");
        });

        // 还原
        ctx.runOnClient(c -> session(c).edit(baseline));
        click(ctx, "同步"); ctx.waitFor(c -> session(c).status.equals("同步成功"));
    }

    // ---------- helpers ----------
    private static void clickSlot(ClientGameTestContext ctx, int slot, boolean doubleClick) {
        ctx.runOnClient(c -> {
            double[] p = ((ItemGridScreen) c.screen).slotCenter(slot);
            c.screen.mouseClicked(new MouseButtonEvent(p[0], p[1], new MouseButtonInfo(0, 0)), doubleClick);
        });
        ctx.waitTicks(2);
    }
    private static EditBox field(Minecraft c, String label) {
        return c.screen.children().stream().filter(w -> w instanceof EditBox e && e.getMessage().getString().equals(label)).map(w -> (EditBox) w)
                .findFirst().orElseThrow(() -> new AssertionError("Missing input: " + label));
    }
    private static boolean hasField(Minecraft c, String label) {
        return c.screen.children().stream().anyMatch(w -> w instanceof EditBox e && e.getMessage().getString().equals(label));
    }
    private static boolean has(Minecraft c, String label, boolean contains) {
        return c.screen.children().stream().anyMatch(w -> w instanceof Button b && (contains ? b.getMessage().getString().contains(label) : b.getMessage().getString().equals(label)));
    }
    private static void search(ClientGameTestContext ctx, String text, String label) { input(ctx, label, text); ctx.waitTick(); }
    private static void input(ClientGameTestContext ctx, String label, String value) { ctx.runOnClient(c -> field(c, label).setValue(value)); }
    private static void inputPage(ClientGameTestContext ctx, String label, String value) {
        for (int i = 0; i < 12; i++) {
            if (ctx.computeOnClient(c -> hasField(c, label))) { input(ctx, label, value); return; }
            navigate(ctx);
        }
        throw new AssertionError("Input not found across pages: " + label);
    }
    private static void clickPage(ClientGameTestContext ctx, String label) { clickPage(ctx, label, false); }
    private static void clickPageContains(ClientGameTestContext ctx, String label) { clickPage(ctx, label, true); }
    private static void clickPage(ClientGameTestContext ctx, String label, boolean contains) {
        for (int i = 0; i < 12; i++) {
            if (ctx.computeOnClient(c -> has(c, label, contains))) { click(ctx, label, contains); return; }
            navigate(ctx);
        }
        throw new AssertionError("Button not found across pages: " + label);
    }
    private static void navigate(ClientGameTestContext ctx) {
        if (ctx.computeOnClient(c -> c.screen.children().stream().anyMatch(w -> w instanceof Button b && b.active && b.getMessage().getString().equals("下一页")))) click(ctx, "下一页");
        else while (ctx.computeOnClient(c -> c.screen.children().stream().anyMatch(w -> w instanceof Button b && b.active && b.getMessage().getString().equals("上一页")))) click(ctx, "上一页");
    }
    private static void clickIfActive(ClientGameTestContext ctx, String label) {
        if (ctx.computeOnClient(c -> c.screen.children().stream().anyMatch(w -> w instanceof Button b && b.active && b.getMessage().getString().equals(label)))) click(ctx, label);
    }
    private static void click(ClientGameTestContext ctx, String label) { click(ctx, label, false); }
    private static void clickContains(ClientGameTestContext ctx, String label) { click(ctx, label, true); }
    private static void click(ClientGameTestContext ctx, String label, boolean contains) {
        ctx.runOnClient(c -> {
            var b = c.screen.children().stream().filter(w -> w instanceof Button button && (contains ? button.getMessage().getString().contains(label) : button.getMessage().getString().equals(label)))
                    .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label + " on " + c.screen.getClass().getSimpleName()));
            check(b.active, "Button enabled: " + label);
            c.screen.mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
        });
        ctx.waitTicks(2);
    }
    private static void clickNth(ClientGameTestContext ctx, String label, int n) {
        ctx.runOnClient(c -> {
            var b = c.screen.children().stream().filter(w -> w instanceof Button button && button.getMessage().getString().equals(label))
                    .map(w -> (Button) w).skip(n).findFirst().orElseThrow(() -> new AssertionError("Missing button #" + n + ": " + label));
            check(b.active, "Button enabled: " + label + " #" + n);
            c.screen.mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
        });
        ctx.waitTicks(2);
    }
}
