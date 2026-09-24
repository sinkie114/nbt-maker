package com.sinkie114.client;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Editor-only SNBT snapshots, not Minecraft's persistence format for out-of-range runtime values. */
public final class StackData {
    private StackData() {}

    public static CompoundTag encode(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return new CompoundTag();
        CompoundTag root = new CompoundTag();
        root.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        root.putInt("count", stack.getCount());
        CompoundTag components = new CompoundTag();
        for (var entry : stack.getComponentsPatch().entrySet()) {
            if (!entry.getKey().isTransient() && entry.getValue().isEmpty())
                components.put("!" + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey()), new CompoundTag());
        }
        // Include defaults, so changing the item id retains all existing serializable components.
        for (TypedDataComponent<?> entry : stack.getComponents()) encodeComponent(entry, components, registries);
        root.put("components", components);
        return root;
    }

    private static <T> void encodeComponent(TypedDataComponent<T> entry, CompoundTag target, HolderLookup.Provider registries) {
        if (entry.type().isTransient()) return;
        target.put(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.type()).toString(),
                EditorComponentData.write(entry.type(), entry.value(), registries));
    }

    public static ItemStack decode(CompoundTag root, HolderLookup.Provider registries) {
        if (root.isEmpty()) return ItemStack.EMPTY;
        for (String key : root.keySet()) if (!Set.of("id", "count", "components").contains(key))
            throw new IllegalArgumentException("ItemStack 无法保存根字段 " + key + "；自定义键请放入 minecraft:custom_data");
        String id = root.getString("id").orElseThrow(() -> new IllegalArgumentException("id 必须是物品 ID 字符串"));
        var itemId = Identifier.parse(id);
        if (!BuiltInRegistries.ITEM.containsKey(itemId)) throw new IllegalArgumentException("未注册的物品：" + id);
        int count = root.contains("count") ? root.getInt("count").orElseThrow(() -> new IllegalArgumentException("count 必须是整数")) : 1;
        Tag components = root.get("components");
        if (components != null && !(components instanceof CompoundTag)) throw new IllegalArgumentException("components 必须是 Compound");
        CompoundTag regular = components == null ? new CompoundTag() : ((CompoundTag)components).copy();
        Map<DataComponentType<?>, Tag> runtime = new LinkedHashMap<>();
        Set<DataComponentType<?>> seen = new HashSet<>();
        for (String key : List.copyOf(regular.keySet())) {
            boolean removed = key.startsWith("!");
            var identifier = Identifier.tryParse(removed ? key.substring(1) : key);
            var type = identifier == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.get(identifier).map(h -> h.value()).orElse(null);
            if (type == null) continue; // Let the vanilla patch codec report unknown IDs.
            if (!seen.add(type)) throw new IllegalArgumentException("重复或同时添加、删除的组件：" + key);
            if (!removed && EditorComponentData.handles(type)) runtime.put(type, regular.remove(key));
        }
        DataComponentPatch patch = DataComponentPatch.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), regular).getOrThrow();
        // Never accept DataResult partial values: they can silently discard unrecognized components.
        ItemStack result = new ItemStack(BuiltInRegistries.ITEM.get(itemId).orElseThrow(), count, patch);
        runtime.forEach((type, value) -> EditorComponentData.apply(result, type, value, registries));
        return result;
    }

    public static Tag at(Tag root, List<Object> path) {
        Tag current = root;
        for (Object key : path) {
            if (current instanceof CompoundTag compound && key instanceof String s) current = compound.get(s);
            else if (current instanceof CollectionTag list && key instanceof Integer i && i >= 0 && i < list.size()) current = list.get(i);
            else return null;
        }
        return current;
    }

    public static List<Object> child(List<Object> path, Object key) {
        var result = new ArrayList<Object>(path); result.add(key); return List.copyOf(result);
    }

    public static CompoundTag replace(CompoundTag root, List<Object> path, Tag value) {
        if (path.isEmpty()) {
            if (!(value instanceof CompoundTag compound)) throw new IllegalArgumentException("物品根节点必须为 Compound");
            return compound.copy();
        }
        CompoundTag copy = root.copy();
        Tag parent = at(copy, path.subList(0, path.size() - 1));
        Object key = path.getLast();
        if (parent instanceof CompoundTag compound && key instanceof String s) compound.put(s, value.copy());
        else if (parent instanceof CollectionTag list && key instanceof Integer i) {
            if (!list.setTag(i, value.copy())) throw new IllegalArgumentException("数组元素类型不匹配");
        } else throw new IllegalArgumentException("节点位置已失效");
        return copy;
    }

    public static CompoundTag remove(CompoundTag root, List<Object> path) {
        if (path.isEmpty()) return new CompoundTag();
        CompoundTag copy = root.copy();
        Tag parent = at(copy, path.subList(0, path.size() - 1));
        Object key = path.getLast();
        if (parent instanceof CompoundTag compound && key instanceof String s) {
            compound.remove(s);
            // Removing an effective component must also suppress the item type's default.
            if (path.size() >= 2 && path.get(path.size() - 2).equals("components") && !s.startsWith("!"))
                compound.put("!" + s, new CompoundTag());
        } else if (parent instanceof CollectionTag list && key instanceof Integer i) list.remove(i);
        else throw new IllegalArgumentException("节点位置已失效");
        return copy;
    }

    public static String summary(Tag value) {
        if (value instanceof CompoundTag compound) return "{" + compound.size() + " 个键}";
        if (value instanceof CollectionTag list) return "[" + list.size() + " 项]";
        return value == null ? "<缺失>" : value.toString();
    }

    public static String type(Tag value) {
        return value == null ? "Missing" : switch (value.getId()) {
            case 1 -> "Byte/布尔"; case 2 -> "Short"; case 3 -> "Int"; case 4 -> "Long";
            case 5 -> "Float"; case 6 -> "Double"; case 7 -> "Byte[]"; case 8 -> "String";
            case 9 -> "List"; case 10 -> "Compound"; case 11 -> "Int[]"; case 12 -> "Long[]"; default -> "End";
        };
    }

    public static boolean risky(CompoundTag root) {
        return !riskReason(root).isEmpty();
    }

    public static String riskReason(CompoundTag root) {
        record Visit(Tag tag, int depth, boolean custom) {}
        var queue = new ArrayDeque<Visit>();
        queue.add(new Visit(root, 0, false)); int nodes = 0;
        while (!queue.isEmpty()) {
            var e = queue.removeFirst();
            if (++nodes > 12000 || e.depth() > 32) return "节点数量或嵌套层数过多";
            Tag t = e.tag();
            if (t instanceof CompoundTag c) {
                if (!e.custom() && c.contains("id") && c.getIntOr("count", 1) > 99) return "物品数量超过 99";
                if (!e.custom() && c.contains("operation") && Math.abs(c.getDoubleOr("amount", 0)) > 1.0E8) return "属性修饰符包含极端数值";
                for (String k : c.keySet()) queue.add(new Visit(c.get(k), e.depth() + 1, e.custom() || k.equals("minecraft:custom_data")));
            } else if (t instanceof CollectionTag c) for (Tag child : c) queue.add(new Visit(child, e.depth() + 1, e.custom()));
            else if (!e.custom() && t instanceof NumericTag n && !Double.isFinite(n.doubleValue())) return "组件包含非有限数值：" + t;
            else if (t instanceof StringTag s && s.value().length() > 32767) return "字符串长度超过 32767";
        }
        return root.sizeInBytes() > 1024 * 1024 ? "数据大小超过 1 MiB" : "";
    }
}
