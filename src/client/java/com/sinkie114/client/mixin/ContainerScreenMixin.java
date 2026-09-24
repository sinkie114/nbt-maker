package com.sinkie114.client.mixin;
import com.sinkie114.client.NbtMakerClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AbstractContainerScreen.class)
public class ContainerScreenMixin {
    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void nbtmaker$keepSource(CallbackInfo ci) {
        if (NbtMakerClient.suspending == (Object) this) ci.cancel();
    }
}
