package com.sinkie114.client;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Builds a vanilla /give command for an item, listing only the components that differ from the item's defaults. */
final class ItemGiveCommand {
    /** Longest command the chat box accepts; command blocks take far more. */
    static final int CHAT_LIMIT = 256;

    private ItemGiveCommand() {}

    /**
     * @param command the full command, starting with "/"
     * @param lossless false when a value only fits the editor's own format (for example an
     *                 enchantment level above 255), so the game may reject the command
     */
    record Result(String command, boolean lossless) {}

    static Result of(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) throw new IllegalArgumentException("物品为空，或当前数据无法生成物品");
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        List<String> components = new ArrayList<>();
        boolean lossless = true;
        for (var entry : stack.getComponentsPatch().entrySet()) {
            DataComponentType<?> type = entry.getKey();
            if (type.isTransient()) continue;
            String key = shortId(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
            if (entry.getValue().isEmpty()) { components.add("!" + key); continue; }
            Tag vanilla = vanilla(type, entry.getValue().get(), ops);
            if (vanilla == null) lossless = false;
            components.add(key + "=" + (vanilla != null ? vanilla : editor(type, entry.getValue().get(), registries)));
        }
        StringBuilder command = new StringBuilder("/give @s ").append(shortId(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        if (!components.isEmpty()) command.append('[').append(String.join(",", components)).append(']');
        if (stack.getCount() != 1) command.append(' ').append(stack.getCount());
        return new Result(command.toString(), lossless);
    }

    /** The message shown after copying, with the caveats that matter for pasting it. */
    static String copiedMessage(Result result) {
        String message = "已复制 /give 命令（" + result.command().length() + " 字符）";
        if (!result.lossless()) message += "；含原版无法解析的数值（如超过 255 级的附魔），执行时可能报错";
        else if (result.command().length() > CHAT_LIMIT) message += "；超过聊天栏 " + CHAT_LIMIT + " 字符上限，请粘贴到命令方块";
        return message;
    }

    private static String shortId(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    @SuppressWarnings("unchecked") // The value comes from the same patch entry as its type.
    private static <T> Tag vanilla(DataComponentType<T> type, Object value, DynamicOps<Tag> ops) {
        return type.codecOrThrow().encodeStart(ops, (T) value).result().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> Tag editor(DataComponentType<T> type, Object value, HolderLookup.Provider registries) {
        return EditorComponentData.write(type, (T) value, registries);
    }
}
