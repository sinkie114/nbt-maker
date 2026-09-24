package com.sinkie114.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.DyeColor;

import java.util.*;
import java.util.function.Consumer;

import static com.sinkie114.client.OptionPickerScreen.Option;

/** 简单模式 · 显示信息：名称、Lore、稀有度、染色、模型、提示框、附魔光效。 */
final class DisplayForms {
    private DisplayForms() {}

    static final Set<String> KEYS = Set.of(
            "minecraft:custom_name", "minecraft:item_name", "minecraft:lore", "minecraft:rarity",
            "minecraft:dyed_color", "minecraft:item_model", "minecraft:custom_model_data",
            "minecraft:tooltip_display", "minecraft:enchantment_glint_override");

    /** 文本组件里除了这些键之外还有别的（translate、extra……），就算"复杂文本"。 */
    private static final Set<String> SIMPLE_TEXT_KEYS = Set.of(
            "text", "type", "color", "bold", "italic", "underlined", "strikethrough", "obfuscated");

    static final List<Option> TEXT_COLORS = List.of(
            new Option("", "默认颜色"),
            new Option("black", "黑色"), new Option("dark_blue", "深蓝"), new Option("dark_green", "深绿"),
            new Option("dark_aqua", "深青"), new Option("dark_red", "深红"), new Option("dark_purple", "紫色"),
            new Option("gold", "金色"), new Option("gray", "灰色"), new Option("dark_gray", "深灰"),
            new Option("blue", "蓝色"), new Option("green", "绿色"), new Option("aqua", "青色"),
            new Option("red", "红色"), new Option("light_purple", "粉紫"), new Option("yellow", "黄色"),
            new Option("white", "白色"));

    static void open(SimpleComponentScreen parent, String key) {
        var session = parent.session();
        Tag existing = parent.components().get(key);
        switch (key) {
            case "minecraft:custom_name", "minecraft:item_name" -> {
                boolean custom = key.equals("minecraft:custom_name");
                textForm(parent, custom ? "自定义名称" : "物品名称", existing, custom,
                        value -> save(parent, key, value));
            }
            case "minecraft:lore" -> session.client.setScreen(new LoreScreen(parent, existing));
            case "minecraft:rarity" -> {
                var options = List.of(new Option("common", "普通（白色名称）"), new Option("uncommon", "罕见（黄色名称）"),
                        new Option("rare", "稀有（青色名称）"), new Option("epic", "史诗（品红名称）"));
                singleValue(parent, key, "稀有度", existing, StringTag.valueOf("common"), form ->
                        form.choice("稀有度", () -> RegistryOptions.name(options, form.draft.getStringOr("value", "common")),
                                () -> options, v -> form.draft.putString("value", v)));
            }
            case "minecraft:enchantment_glint_override" -> {
                var options = List.of(new Option("true", "强制发光"), new Option("false", "强制不发光"));
                singleValue(parent, key, "附魔光效", existing, ByteTag.ONE, form ->
                        form.choice("光效", () -> form.draft.getBooleanOr("value", true) ? "强制发光" : "强制不发光",
                                () -> options, v -> form.draft.putBoolean("value", Boolean.parseBoolean(v))));
            }
            case "minecraft:item_model" -> {
                var items = RegistryOptions.items();
                singleValue(parent, key, "物品模型", existing, StringTag.valueOf("minecraft:stone"), form -> {
                    form.choice("使用某个物品的模型", () -> form.draft.getStringOr("value", ""), () -> items,
                            v -> form.draft.putString("value", v));
                    form.input("或填写模型 ID（资源包模型，留空则用上面的选择）", "例如 mypack:ruby_sword", "",
                            (result, text) -> { if (!text.isBlank()) result.putString("value", text.trim()); });
                });
            }
            case "minecraft:dyed_color" -> {
                var dyes = new ArrayList<Option>();
                for (DyeColor dye : DyeColor.values())
                    dyes.add(new Option(Integer.toString(dye.getTextureDiffuseColor() & 0xFFFFFF), dyeName(dye), hex(dye.getTextureDiffuseColor())));
                singleValue(parent, key, "染色颜色", existing, IntTag.valueOf(0xA06540), form -> {
                    form.choice("染料颜色", () -> hex(form.draft.getIntOr("value", 0)), () -> dyes,
                            v -> form.draft.putInt("value", Integer.parseInt(v)));
                    form.input("或填写十六进制颜色（留空则用上面的选择）", "例如 #FF0000", "",
                            (result, text) -> { if (!text.isBlank()) result.putInt("value", parseHex(text)); });
                });
            }
            case "minecraft:custom_model_data" -> customModelData(parent, key, existing);
            case "minecraft:tooltip_display" -> tooltipDisplay(parent, key, existing);
            default -> throw new IllegalArgumentException("没有对应表单");
        }
    }

    // ---------- 文本组件（名称 / Lore 行） ----------

    /**
     * 编辑一个文本组件：文字、颜色、粗体/斜体/下划线/删除线/乱码。
     * italicByDefault：自定义名称和 Lore 在原版里默认是斜体，打开时显式写入 italic:false，所见即所得。
     */
    static void textForm(Screen back, String title, Tag current, boolean italicByDefault, Consumer<Tag> commit) {
        var client = ((EditorLayer) back).session().client;
        CompoundTag draft = textDraft(current, client);
        boolean complex = current instanceof CompoundTag c && !SIMPLE_TEXT_KEYS.containsAll(c.keySet()) || current instanceof ListTag;
        if (italicByDefault && !draft.contains("italic")) draft.putBoolean("italic", false);
        String originalText = draft.getStringOr("text", "");
        var form = new EditorFormScreen(back, title, draft, result -> {
            CompoundTag out = result.copy();
            if (out.getStringOr("color", "x").isEmpty()) out.remove("color");
            // 文字没改动的复杂文本：保留原结构，只覆盖样式键。
            if (complex && out.getStringOr("text", "").equals(originalText) && current instanceof CompoundTag original) {
                CompoundTag merged = original.copy();
                for (String k : List.of("color", "bold", "italic", "underlined", "strikethrough", "obfuscated")) {
                    if (out.contains(k)) merged.put(k, out.get(k).copy()); else merged.remove(k);
                }
                out = merged;
            } else {
                for (String k : List.copyOf(out.keySet())) if (!SIMPLE_TEXT_KEYS.contains(k)) out.remove(k);
                out.remove("type");
            }
            ComponentSerialization.CODEC.parse(client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), out).getOrThrow();
            commit.accept(out);
        });
        form.input("文字" + (complex ? "（原文本含翻译/多段结构，改文字会变成单段纯文本）" : ""), "直接输入文字，不需要引号",
                originalText, (result, text) -> result.putString("text", text));
        form.choice("颜色", () -> {
            String color = form.draft.getStringOr("color", "");
            return color.startsWith("#") ? color : RegistryOptions.name(TEXT_COLORS, color);
        }, () -> TEXT_COLORS, v -> { if (v.isEmpty()) form.draft.remove("color"); else form.draft.putString("color", v); });
        form.input("自定义十六进制颜色（留空则用上面的颜色）", "例如 #FF8800", "", (result, text) -> {
            if (!text.isBlank()) result.putString("color", hex(parseHex(text)));
        });
        form.toggle("bold", "粗体", false)
                .toggle("italic", "斜体", false)
                .toggle("underlined", "下划线", false)
                .toggle("strikethrough", "删除线", false)
                .toggle("obfuscated", "乱码（闪烁字符）", false);
        client.setScreen(form);
    }

    /** 把任意形态的文本组件（字符串 / 复合 / 列表）转成表单草稿。 */
    static CompoundTag textDraft(Tag current, net.minecraft.client.Minecraft client) {
        if (current instanceof StringTag s) { var c = new CompoundTag(); c.putString("text", s.value()); return c; }
        if (current instanceof CompoundTag c) {
            var copy = c.copy();
            if (!copy.contains("text")) copy.putString("text", plain(current, client));
            return copy;
        }
        var c = new CompoundTag();
        c.putString("text", current == null ? "" : plain(current, client));
        return c;
    }

    /** 文本组件的纯文字（翻译键会按当前语言显示）。 */
    static String plain(Tag value, net.minecraft.client.Minecraft client) {
        try {
            return ComponentSerialization.CODEC.parse(client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), value)
                    .getOrThrow().getString();
        } catch (RuntimeException ex) { return value.toString(); }
    }

    /** 带样式的 Component，用于在按钮上预览 Lore 行。 */
    static Component styled(Tag value, net.minecraft.client.Minecraft client) {
        try {
            return ComponentSerialization.CODEC.parse(client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), value).getOrThrow();
        } catch (RuntimeException ex) { return Component.literal("格式错误：" + value); }
    }

    // ---------- 单值组件 ----------

    /** 稀有度、光效、模型、染色这类"一个值"的组件：草稿里放 {value: ...}，保存时取出。 */
    private static void singleValue(SimpleComponentScreen parent, String key, String title, Tag existing, Tag fallback,
                                    Consumer<EditorFormScreen> fields) {
        CompoundTag draft = new CompoundTag();
        draft.put("value", existing == null ? fallback.copy() : existing.copy());
        var form = new EditorFormScreen(parent, title, draft, result -> save(parent, key, result.get("value")));
        fields.accept(form);
        parent.session().client.setScreen(form);
    }

    private static void customModelData(SimpleComponentScreen parent, String key, Tag existing) {
        CompoundTag data = existing instanceof CompoundTag c ? c.copy() : new CompoundTag();
        var form = new EditorFormScreen(parent, "自定义模型数据（资源包用）", data, result -> save(parent, key, result));
        form.input("floats（逗号分隔的小数）", "例如 1, 2.5", joinNumbers(data.get("floats"), false), (result, text) -> {
            var list = new ListTag();
            for (String part : split(text)) list.add(FloatTag.valueOf((float) EditorFormScreen.numeric(part, "floats", -Float.MAX_VALUE, Float.MAX_VALUE)));
            result.put("floats", list);
        });
        form.input("flags（逗号分隔的 true/false）", "例如 true, false", joinFlags(data.get("flags")), (result, text) -> {
            var list = new ListTag();
            for (String part : split(text)) {
                if (!part.equalsIgnoreCase("true") && !part.equalsIgnoreCase("false")) throw new IllegalArgumentException("flags 只能填 true 或 false。");
                list.add(ByteTag.valueOf(Boolean.parseBoolean(part)));
            }
            result.put("flags", list);
        });
        form.input("strings（逗号分隔的文字）", "例如 ruby, glowing", joinStrings(data.get("strings")), (result, text) -> {
            var list = new ListTag();
            for (String part : split(text)) list.add(StringTag.valueOf(part));
            result.put("strings", list);
        });
        form.input("colors（逗号分隔的十六进制颜色）", "例如 #FF0000, #00FF00", joinColors(data.get("colors")), (result, text) -> {
            var list = new ListTag();
            for (String part : split(text)) list.add(IntTag.valueOf(parseHex(part)));
            result.put("colors", list);
        });
        parent.session().client.setScreen(form);
    }

    private static void tooltipDisplay(SimpleComponentScreen parent, String key, Tag existing) {
        CompoundTag data = existing instanceof CompoundTag c ? c.copy() : new CompoundTag();
        if (!data.contains("hidden_components")) data.put("hidden_components", new ListTag());
        var form = new EditorFormScreen(parent, "提示框显示", data, result -> save(parent, key, result));
        form.toggle("hide_tooltip", "完全隐藏提示框", false);
        form.action("隐藏部分提示行", () -> "选择要隐藏的组件（" + ComponentForms.listSize(form.draft, "hidden_components") + " 项）",
                () -> parent.session().client.setScreen(new MultiPickerScreen(form, "hidden_components", "隐藏哪些提示行", tooltipOptions())), true);
        parent.session().client.setScreen(form);
    }

    /** 常见会出现在提示框里的组件排前面，其余按 ID 排序。 */
    static List<Option> tooltipOptions() {
        var names = new LinkedHashMap<String, String>();
        names.put("minecraft:enchantments", "附魔");
        names.put("minecraft:stored_enchantments", "存储附魔");
        names.put("minecraft:attribute_modifiers", "属性修饰符");
        names.put("minecraft:unbreakable", "不可破坏");
        names.put("minecraft:dyed_color", "染色");
        names.put("minecraft:trim", "盔甲纹饰");
        names.put("minecraft:lore", "Lore 描述");
        names.put("minecraft:can_break", "可破坏方块（冒险模式）");
        names.put("minecraft:can_place_on", "可放置于（冒险模式）");
        names.put("minecraft:potion_contents", "药水效果");
        names.put("minecraft:damage", "耐久");
        names.put("minecraft:container", "容器内容");
        names.put("minecraft:bundle_contents", "收纳袋内容");
        names.put("minecraft:charged_projectiles", "已装填发射物");
        names.put("minecraft:fireworks", "烟花");
        names.put("minecraft:jukebox_playable", "唱片");
        names.put("minecraft:written_book_content", "成书");
        var result = new ArrayList<Option>();
        names.forEach((id, name) -> result.add(new Option(id, name)));
        BuiltInRegistries.DATA_COMPONENT_TYPE.entrySet().stream()
                .filter(e -> !e.getValue().isTransient())
                .map(e -> e.getKey().identifier().toString())
                .filter(id -> !names.containsKey(id)).sorted()
                .forEach(id -> result.add(new Option(id, id.startsWith("minecraft:") ? id.substring(10) : id)));
        return result;
    }

    // ---------- 工具 ----------

    static void save(SimpleComponentScreen parent, String key, Tag value) {
        ComponentForms.validate(parent.session(), key, value);
        parent.setComponent(key, value);
    }

    static int parseHex(String text) {
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (!hex.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("颜色请输入六位十六进制，例如 #FF0000。");
        return Integer.parseInt(hex, 16);
    }

    static String hex(int rgb) { return String.format("#%06X", rgb & 0xFFFFFF); }

    private static List<String> split(String text) {
        var parts = new ArrayList<String>();
        for (String p : text.split("[,，]")) if (!p.isBlank()) parts.add(p.trim());
        return parts;
    }

    private static String joinNumbers(Tag list, boolean integer) {
        var parts = new ArrayList<String>();
        if (list instanceof ListTag l) for (Tag t : l) if (t instanceof NumericTag n) parts.add(integer ? Long.toString(n.longValue()) : Float.toString(n.floatValue()));
        return String.join(", ", parts);
    }

    private static String joinFlags(Tag list) {
        var parts = new ArrayList<String>();
        if (list instanceof ListTag l) for (Tag t : l) if (t instanceof NumericTag n) parts.add(n.byteValue() != 0 ? "true" : "false");
        return String.join(", ", parts);
    }

    private static String joinStrings(Tag list) {
        var parts = new ArrayList<String>();
        if (list instanceof ListTag l) for (Tag t : l) if (t instanceof StringTag s) parts.add(s.value());
        return String.join(", ", parts);
    }

    private static String joinColors(Tag list) {
        var parts = new ArrayList<String>();
        if (list instanceof ListTag l) for (Tag t : l) if (t instanceof NumericTag n) parts.add(hex(n.intValue()));
        return String.join(", ", parts);
    }

    private static String dyeName(DyeColor dye) {
        return switch (dye) {
            case WHITE -> "白色"; case ORANGE -> "橙色"; case MAGENTA -> "品红"; case LIGHT_BLUE -> "淡蓝";
            case YELLOW -> "黄色"; case LIME -> "黄绿"; case PINK -> "粉红"; case GRAY -> "灰色";
            case LIGHT_GRAY -> "淡灰"; case CYAN -> "青色"; case PURPLE -> "紫色"; case BLUE -> "蓝色";
            case BROWN -> "棕色"; case GREEN -> "绿色"; case RED -> "红色"; case BLACK -> "黑色";
        };
    }
}
