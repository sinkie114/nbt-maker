package com.sinkie114.client.mixin;

import com.sinkie114.client.EditorLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    @Redirect(method = "clientSideCloseContainer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void nbtmaker$keepFailedDraft(Minecraft client, Screen next) {
        // Vanilla has already closed the actual menu. Keep only our independent document on screen.
        if (client.screen instanceof EditorLayer layer) {
            layer.session().valid = false;
            layer.session().changed = true;
        } else client.setScreen(next);
    }
}
