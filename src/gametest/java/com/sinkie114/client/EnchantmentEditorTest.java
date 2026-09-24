package com.sinkie114.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.enchantment.*;
import java.util.*;

final class EnchantmentEditorTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static EditSession session(Minecraft c) { return ((EditorLayer)c.screen).session(); }

    static void exercise(ClientGameTestContext ctx, TestServerContext server) {
        server.runOnServer(s -> memoryAndCodecChecks(s.registryAccess()));
        CompoundTag baseline = ctx.computeOnClient(c -> session(c).document.copy());
        click(ctx,"附魔"); click(ctx,"附魔（可继续新增附魔 ID）");
        input(ctx,"搜索附魔","minecraft:sharpness");
        for (int level : new int[]{Integer.MIN_VALUE,-1000,-1,0,255,256,1000,Integer.MAX_VALUE}) {
            click(ctx,"等级"); input(ctx,"等级",Integer.toString(level));
            if (level == Integer.MIN_VALUE) ctx.takeScreenshot("nbt-enchantment-int-min-input");
            if (level == Integer.MAX_VALUE) ctx.takeScreenshot("nbt-enchantment-int-max-input");
            click(ctx,"保存到副本");
            ctx.runOnClient(c -> {
                check(c.screen instanceof EnchantmentScreen,"Level form returns to list");
                check(session(c).error.isEmpty(),"Runtime preview builds: " + session(c).error);
                var sharpness = c.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
                check(session(c).preview.get(DataComponents.ENCHANTMENTS).getLevel(sharpness) == level,"Exact GUI level " + level);
                check(field(c,"搜索附魔").getValue().equals("minecraft:sharpness"),"Search survives level editing");
                check(c.screen.children().stream().anyMatch(w -> w instanceof Button b && b.getMessage().getString().endsWith("等级 " + level)),
                        "Visible level updates immediately");
            });
        }
        CompoundTag maxDraft = ctx.computeOnClient(c -> session(c).document.copy());
        click(ctx,"等级");
        for (String invalid : List.of("2147483648","-2147483649","1.5","-1.5","2147483647.0000000001","-2147483648.0000000001","1e3","NaN","")) {
            input(ctx,"等级",invalid); click(ctx,"保存到副本");
            ctx.runOnClient(c -> {
                check(c.screen instanceof EditorFormScreen,"Reject invalid integer without closing the form: " + invalid);
                check(session(c).document.equals(maxDraft),"Invalid input cannot change the draft");
            });
        }
        click(ctx,"取消");
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0)
                .get(DataComponents.ENCHANTMENTS).getLevel(s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)) == 5,
                "Draft editing never mutates the source or shared component"));
        ctx.takeScreenshot("nbt-enchantment-int-max-list");
        click(ctx,"返回");
        click(ctx,"存储附魔"); input(ctx,"搜索附魔","minecraft:sharpness"); click(ctx,"添加");
        for (int level : new int[]{Integer.MAX_VALUE,256,0,-1,Integer.MIN_VALUE}) {
            click(ctx,"等级"); input(ctx,"等级",Integer.toString(level)); click(ctx,"保存到副本");
            ctx.runOnClient(c -> {
                var sharpness = c.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
                check(session(c).preview.get(DataComponents.STORED_ENCHANTMENTS).getLevel(sharpness) == level,"Stored enchantment GUI keeps signed level " + level);
                check(session(c).preview.get(DataComponents.ENCHANTMENTS).getLevel(sharpness) == Integer.MAX_VALUE,"Normal and stored enchantments stay independent");
            });
            CompoundTag draft = ctx.computeOnClient(c -> session(c).document.copy());
            server.runOnServer(s -> {
                // Same memory conversion used by sync, in a slot without a network synchronizer.
                // Vanilla out-of-range packet rejection is checked separately below.
                var container = new net.minecraft.world.SimpleContainer(1);
                container.setItem(0,StackData.decode(draft,s.registryAccess()));
                var item = container.getItem(0);
                var sharpness = s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
                check(item.get(DataComponents.STORED_ENCHANTMENTS).getLevel(sharpness) == level,"GUI draft reaches server-side item memory with its exact signed level");
                check(item.get(DataComponents.ENCHANTMENTS).getLevel(sharpness) == Integer.MAX_VALUE,"Normal int max survives memory write");
                check(StackData.encode(item,s.registryAccess()).equals(draft),"Memory readback retains the full GUI snapshot");
            });
        }
        ctx.takeScreenshot("nbt-enchantment-int-min-list");
        click(ctx,"返回"); click(ctx,"高级模式");
        ctx.runOnClient(c -> check(session(c).preview.get(DataComponents.STORED_ENCHANTMENTS).getLevel(
                c.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)) == Integer.MIN_VALUE,"Mode switch keeps the negative endpoint"));
        click(ctx,"简单模式"); click(ctx,"附魔"); click(ctx,"存储附魔");
        input(ctx,"搜索附魔","minecraft:sharpness"); click(ctx,"删除");
        ctx.runOnClient(c -> {
            check(c.screen.children().stream().anyMatch(w -> w instanceof Button b && b.getMessage().getString().equals("添加")),"Deleting an int-min enchantment refreshes immediately");
            check(session(c).preview.get(DataComponents.STORED_ENCHANTMENTS).isEmpty(),"Negative enchantment can be removed");
        });
        click(ctx,"返回");
        // In-range live sync, then out-of-range network sync.
        ctx.runOnClient(c -> {
            var normal = baseline.copy();
            normal.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:enchantments").putInt("minecraft:sharpness",255);
            session(c).edit(normal);
        });
        click(ctx,"同步"); ctx.waitFor(c -> session(c).status.equals("同步成功"));
        server.runOnServer(s -> check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(0).get(DataComponents.ENCHANTMENTS)
                .getLevel(s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)) == 255,"In-range sync still writes the original inventory slot"));
        // 超范围等级经真实网络同步到客户端：不断线，客户端收到的等级与服务端一致。
        for (int level : new int[]{1000, Integer.MAX_VALUE, -5}) {
            ctx.runOnClient(c -> {
                var big = baseline.copy();
                big.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:enchantments").putInt("minecraft:sharpness",level);
                session(c).edit(big);
            });
            click(ctx,"同步"); ctx.waitFor(c -> session(c).status.equals("同步成功"));
            ctx.waitTicks(10);
            ctx.runOnClient(c -> {
                check(c.getConnection() != null && c.getConnection().getConnection().isConnected(),"Client stays connected after out-of-range sync " + level);
                var sharp = c.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
                check(c.player.getInventory().getItem(0).get(DataComponents.ENCHANTMENTS).getLevel(sharp) == level,"Client received exact level over the network: " + level);
            });
        }
        ctx.runOnClient(c -> session(c).edit(baseline));
        click(ctx,"同步"); ctx.waitFor(c -> session(c).status.equals("同步成功"));
    }

    private static void memoryAndCodecChecks(net.minecraft.core.RegistryAccess registries) {
        var sharpness = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (int level : new int[]{Integer.MIN_VALUE,-1000,-1,0,1,255,256,1000,Integer.MAX_VALUE}) {
            boolean invalidCodecLevel = level < 1 || level > 255;
            var data = new CompoundTag(); data.putInt("minecraft:sharpness",level);
            ItemEnchantments runtime = RuntimeEnchantments.read(data,registries);
            check(runtime.getLevel(sharpness) == level,"Memory factory does not clamp");
            boolean ctorRejects = false;
            try { var m = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>>(); m.put(sharpness,level); com.sinkie114.client.mixin.EnchantmentsMixin.nbtmaker$create(m); }
            catch (IllegalArgumentException ex) { ctorRejects = true; }
            check(ctorRejects == (level < 0 || level > 255),"Vanilla constructor validation is untouched");
            check(ItemEnchantments.CODEC.parse(ops,data).error().isPresent() == invalidCodecLevel,"Vanilla enchantment decoding keeps its 1..255 range");
            check(ItemEnchantments.CODEC.encodeStart(ops,runtime).error().isPresent() == invalidCodecLevel,"Vanilla enchantment encoding keeps its 1..255 range");
            var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY); mutable.set(sharpness,level);
            check(mutable.toImmutable().getLevel(sharpness) == Math.max(0,Math.min(level,255)),"Vanilla enchantment setter is unchanged");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),registries);
            try {
                ItemEnchantments.STREAM_CODEC.encode(buffer,runtime);
                boolean rejected = false;
                try { check(ItemEnchantments.STREAM_CODEC.decode(buffer).getLevel(sharpness) == level,"Network round trip keeps the exact int level"); }
                catch (IllegalArgumentException ex) { rejected = true; }
                check(!rejected,"Network decoder accepts any int level: " + level);
            } finally { buffer.release(); }
            ItemStack book = new ItemStack(Items.ENCHANTED_BOOK); book.set(DataComponents.STORED_ENCHANTMENTS,runtime);
            book.set(DataComponents.ENCHANTMENTS,runtime);
            var snapshot = StackData.encode(book,registries);
            ItemStack copy = StackData.decode(snapshot,registries);
            check(ItemStack.matches(book,copy),"Editor snapshot preserves both components exactly");
            check(ItemStack.CODEC.encodeStart(ops,book).error().isPresent() == invalidCodecLevel,"Item persistence codec is unchanged");
            snapshot.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:stored_enchantments").putInt("minecraft:sharpness",2);
            ItemStack edited = StackData.decode(snapshot,registries);
            check(edited.get(DataComponents.STORED_ENCHANTMENTS).getLevel(sharpness) == 2,"Independent replacement value");
            check(copy.get(DataComponents.STORED_ENCHANTMENTS).getLevel(sharpness) == level && book.get(DataComponents.STORED_ENCHANTMENTS).getLevel(sharpness) == level,
                    "Editing snapshots cannot mutate an existing component");
            check(ItemEnchantments.EMPTY.isEmpty() && new ItemStack(Items.ENCHANTED_BOOK).get(DataComponents.STORED_ENCHANTMENTS).isEmpty(),"Shared defaults stay empty");
            if (level == Integer.MAX_VALUE || level == Integer.MIN_VALUE) nestedChecks(book,registries);
        }
        for (Tag invalid : List.of(LongTag.valueOf(2147483648L),LongTag.valueOf(-2147483649L),DoubleTag.valueOf(1.5),StringTag.valueOf("1000"))) {
            var data = new CompoundTag(); data.put("minecraft:sharpness",invalid);
            boolean rejected = false;
            try { RuntimeEnchantments.read(data,registries); } catch (IllegalArgumentException ex) { rejected = true; }
            check(rejected,"Snapshot must reject overflow, fractions and nonnumeric levels");
        }
        var unknown = new CompoundTag(); unknown.putInt("missing_mod:enchantment",1);
        boolean rejected = false;
        try { RuntimeEnchantments.read(unknown,registries); } catch (IllegalArgumentException ex) { rejected = true; }
        check(rejected,"An unknown enchantment cannot silently disappear");
    }

    private static void nestedChecks(ItemStack book, HolderLookup.Provider registries) {
        ItemStack outer = new ItemStack(Items.SHULKER_BOX);
        outer.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(ItemStack.EMPTY,book)));
        outer.set(DataComponents.BUNDLE_CONTENTS,new BundleContents(List.of(book.copy())));
        outer.set(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.of(List.of(book.copy())));
        outer.set(DataComponents.USE_REMAINDER,new UseRemainder(book.copy()));
        outer.remove(DataComponents.ITEM_NAME);
        var custom = new CompoundTag(); custom.put("components",RuntimeEnchantments.write(book.get(DataComponents.ENCHANTMENTS)));
        outer.set(DataComponents.CUSTOM_DATA,CustomData.of(custom));
        CompoundTag snapshot = StackData.encode(outer,registries);
        ItemStack copy = StackData.decode(snapshot,registries);
        check(ItemStack.matches(outer,copy),"Nested containers, bundles, projectiles and remainders keep full levels and component hashes");
        check(!copy.has(DataComponents.ITEM_NAME),"Removed defaults remain removed");
        check(copy.get(DataComponents.CUSTOM_DATA).copyTag().equals(custom),"Opaque custom data is not traversed or rewritten");
        check(copy.get(DataComponents.CONTAINER).stream().toList().getFirst().isEmpty(),"Sparse container slots retain their indices");
    }

    private static EditBox field(Minecraft c, String label) {
        return c.screen.children().stream().filter(w -> w instanceof EditBox b && b.getMessage().getString().equals(label))
                .map(w -> (EditBox)w).findFirst().orElseThrow(() -> new AssertionError("Missing input " + label));
    }
    private static void input(ClientGameTestContext ctx, String label, String text) { ctx.runOnClient(c -> field(c,label).setValue(text)); ctx.waitTick(); }
    private static void click(ClientGameTestContext ctx, String label) {
        ctx.runOnClient(c -> {
            var button = c.screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(label))
                    .map(w -> (Button)w).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
            check(button.active,"Button is enabled: " + label);
            c.screen.mouseClicked(new MouseButtonEvent(button.getX()+4,button.getY()+4,new MouseButtonInfo(0,0)),false);
        }); ctx.waitTicks(2);
    }
}
