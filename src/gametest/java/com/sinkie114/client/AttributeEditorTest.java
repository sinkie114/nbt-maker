package com.sinkie114.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Items;
import java.util.List;

final class AttributeEditorTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static EditSession session(net.minecraft.client.Minecraft client) { return ((EditorLayer) client.screen).session(); }
    private static ListTag entries(EditSession session) { return (ListTag) session.document.getCompoundOrEmpty("components").get(AttributeModifierScreen.KEY); }

    static void exercise(ClientGameTestContext context, TestServerContext server) {
        CompoundTag initial = context.computeOnClient(c -> session(c).document.copy());
        click(context, "高级模式");
        context.runOnClient(c -> {
            check(c.screen instanceof ItemEditorScreen, "Advanced mode must retain the original editor");
            check(c.screen.children().stream().noneMatch(child -> child instanceof Button b &&
                    (b.getMessage().getString().equals("组件面板") || b.getMessage().getString().equals("组件模板"))), "Remove all component panel entrances");
        });
        click(context, "简单模式");
        click(context, "属性"); click(context, "属性修饰符");
        check(context.computeOnClient(c -> c.screen instanceof AttributeModifierScreen), "Dedicated visual attribute editor opens");
        click(context, "编辑");
        context.runOnClient(c -> {
            String number = field(c, "数值").getValue();
            check(Double.parseDouble(number) == ((CompoundTag) entries(session(c)).getFirst()).getDoubleOr("amount", 0), "Existing numeric tag is displayed correctly");
        });
        click(context, "保存到副本");
        context.runOnClient(c -> check(session(c).document.equals(initial), "Opening and saving must not reset operation, slot or amount"));
        click(context, "编辑");
        context.runOnClient(c -> field(c, "数值").setValue("not-a-number"));
        click(context, "保存到副本");
        context.runOnClient(c -> {
            check(session(c).document.equals(initial), "Invalid numbers must leave the draft untouched");
            check(c.screen.getTitle().getString().equals("编辑属性修饰符"), "Invalid input stays in the form");
            field(c, "数值").setValue("-3.125");
        });
        click(context, "保存到副本");
        CompoundTag afterAmount = context.computeOnClient(c -> {
            CompoundTag expected = initial.copy();
            ((CompoundTag) entriesFrom(expected).getFirst()).putDouble("amount", -3.125);
            check(session(c).document.equals(expected), "Editing one amount preserves every other field and modifier");
            return session(c).document.copy();
        });
        // Canceling a new entry must not create a default modifier.
        click(context, "新增修饰符");
        setSearch(context, "nbt-maker-test:");
        clickContains(context, "nbt-maker-test:extra_attribute");
        click(context, "取消");
        context.runOnClient(c -> check(session(c).document.equals(afterAmount), "Canceling a new modifier has no side effects"));

        click(context, "新增修饰符");
        setSearch(context, "nbt-maker-test:");
        context.takeScreenshot("nbt-attribute-mod-search");
        clickContains(context, "nbt-maker-test:extra_attribute");
        context.runOnClient(c -> field(c, "数值").setValue("0.25"));
        click(context, "直接加值");
        clickContains(context, "add_multiplied_total");
        click(context, "主手");
        setSearch(context, "鞍");
        clickContains(context, "saddle");
        context.takeScreenshot("nbt-attribute-form");
        // Resize and open a selector without losing in-progress form values.
        context.runOnClient(c -> { c.options.guiScale().set(4); c.resizeDisplay(); });
        context.waitTick(); context.takeScreenshot("nbt-attribute-form-small");
        context.runOnClient(c -> {
            check(field(c, "数值").getValue().equals("0.25"), "Resize retains numeric input");
            c.options.guiScale().set(2); c.resizeDisplay();
        });
        click(context, "保存到副本");
        context.runOnClient(c -> {
            ListTag list = entries(session(c)); var last = (CompoundTag) list.getLast();
            check(last.getStringOr("type", "").equals("nbt-maker-test:extra_attribute"), "Selection includes a real mod registered attribute");
            check(last.getStringOr("operation", "").equals("add_multiplied_total"), "Operation selection is saved");
            check(last.getStringOr("slot", "").equals("saddle"), "All runtime equipment groups are available");
            check(last.getDoubleOr("amount", 0) == 0.25, "Selected values survive selector and resize");
            check(session(c).error.isEmpty(), "Generated component decodes in 1.21.11: " + session(c).error);
        });
        // Add another modifier, with a distinct generated identifier.
        click(context, "新增修饰符"); setSearch(context, "minecraft:attack_speed");
        clickContains(context, "minecraft:attack_speed"); click(context, "保存到副本");
        context.runOnClient(c -> {
            ListTag list = entries(session(c));
            check(!((CompoundTag)list.getLast()).getStringOr("id", "").equals(((CompoundTag)list.get(list.size()-2)).getStringOr("id", "")),
                    "New modifiers automatically get distinct IDs");
        });
        // Deletion must redraw the active list and use current indexes on consecutive clicks.
        int beforeDelete = context.computeOnClient(c -> entries(session(c)).size());
        click(context, "删除"); click(context, "删除");
        context.runOnClient(c -> check(entries(session(c)).size() == beforeDelete - 2, "Consecutive deletes remove exactly two entries"));
        context.takeScreenshot("nbt-attribute-list");
        click(context, "返回");
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).is(Items.DIAMOND_SWORD), "Draft stays local until sync"));
        click(context, "同步");
        context.waitFor(c -> session(c).status.equals("同步成功"));
        CompoundTag synchronizedDocument = context.computeOnClient(c -> session(c).document.copy());
        server.runOnServer(s -> check(StackData.encode(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0), s.registryAccess()).equals(synchronizedDocument),
                "Visual editing reaches the actual server stack"));
        // Verify pagination, retaining unrelated/optional entry fields and nondefault slots on edits.
        context.runOnClient(c -> {
            CompoundTag document = session(c).document.copy();
            ListTag many = new ListTag();
            for (int i = 0; i < 22; i++) {
                var entry = new CompoundTag();
                entry.putString("type", "minecraft:attack_damage"); entry.putString("id", "nbt-maker-test:page_" + i);
                entry.putDouble("amount", -0.75); entry.putString("operation", "add_multiplied_base"); entry.putString("slot", "offhand");
                entry.putString("future_field", "preserve_me"); many.add(entry);
            }
            document.getCompoundOrEmpty("components").put(AttributeModifierScreen.KEY, many);
            session(c).edit(document);
        });
        click(context, "属性修饰符");
        int pageStart = context.computeOnClient(c -> (int)c.screen.children().stream()
                .filter(child -> child instanceof Button b && b.getMessage().getString().equals("编辑")).count());
        click(context, "下一页"); click(context, "编辑");
        context.runOnClient(c -> {
            check(field(c, "修饰符 ID").getValue().equals("nbt-maker-test:page_" + pageStart), "Pagination targets the actual entry");
            field(c, "数值").setValue("-0.5");
        });
        click(context, "保存到副本");
        context.runOnClient(c -> {
            var entry = (CompoundTag) entries(session(c)).get(pageStart);
            check(entry.getDoubleOr("amount", 0) == -0.5, "Paged edit is saved to correct index");
            check(entry.getStringOr("slot", "").equals("offhand") && entry.getStringOr("operation", "").equals("add_multiplied_base"), "Nondefault operations and slots are retained");
            check(entry.getStringOr("future_field", "").equals("preserve_me"), "Unrecognized entry fields are retained");
        });
        click(context, "返回");
        context.runOnClient(c -> session(c).edit(initial));
        click(context, "同步"); context.waitFor(c -> session(c).status.equals("同步成功"));
    }

    private static ListTag entriesFrom(CompoundTag root) { return (ListTag) root.getCompoundOrEmpty("components").get(AttributeModifierScreen.KEY); }
    private static EditBox field(net.minecraft.client.Minecraft client, String label) {
        return client.screen.children().stream().filter(c -> c instanceof EditBox e && e.getMessage().getString().equals(label))
                .map(c -> (EditBox)c).findFirst().orElseThrow(() -> new AssertionError("Missing input " + label));
    }
    private static void setSearch(ClientGameTestContext context, String value) {
        context.runOnClient(c -> field(c, "搜索选项").setValue(value)); context.waitTick();
    }
    private static void clickContains(ClientGameTestContext context, String text) { click(context, text, true); }
    private static void click(ClientGameTestContext context, String text) { click(context, text, false); }
    private static void click(ClientGameTestContext context, String text, boolean contains) {
        context.runOnClient(c -> {
            Button button = c.screen.children().stream().filter(child -> child instanceof Button b && (contains ? b.getMessage().getString().contains(text) : b.getMessage().getString().equals(text)))
                    .map(child -> (Button)child).findFirst().orElseThrow(() -> new AssertionError("Missing button " + text));
            check(button.active, "Button is enabled: " + text);
            c.screen.mouseClicked(new MouseButtonEvent(button.getX()+button.getWidth()/2.0, button.getY()+button.getHeight()/2.0, new MouseButtonInfo(0,0)), false);
        }); context.waitTicks(2);
    }
}
