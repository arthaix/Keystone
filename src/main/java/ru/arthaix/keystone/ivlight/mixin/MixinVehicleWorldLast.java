package ru.arthaix.keystone.ivlight.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import ru.arthaix.keystone.ivlight.GlReset;

/** Immersive Vehicles' world rendering starts from a GL state that matches GlStateManager's cache (see GlReset). */
@Mixin(targets = "mcinterface1122.InterfaceEventsEntityRendering", remap = false)
public abstract class MixinVehicleWorldLast {
    @Inject(method = "on(Lnet/minecraftforge/client/event/RenderWorldLastEvent;)V", at = @At("HEAD"))
    private static void keystone$cleanGlState(RenderWorldLastEvent event, CallbackInfo ci) {
        GlReset.beforeVehicles();
    }
}
