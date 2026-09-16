package ru.arthaix.keystone.ivlight;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

/**
 * Immersive Vehicles draws every vehicle, sign and pole in RenderWorldLastEvent. It turns the standard item lighting on
 * through GlStateManager, which skips any GL call whose state its cache already holds. Other RenderWorldLastEvent
 * renderers that switch GL state directly (selection outlines, waypoint beams and the like) leave that cache wrong, and
 * only while they have something in view, so Immersive Vehicles' lighting call did nothing in some frames: signs and
 * poles flipped between shaded and flat bright as the camera moved.
 *
 * Before Immersive Vehicles draws, each state it depends on is switched both ways, which makes the real GL state match
 * the cache again, and left as vanilla has it at that point (lighting, lights and colour material off for
 * RenderHelper to turn on, lightmap unit off, fog and blending off, depth, culling and alpha test on).
 */
public final class GlReset {
    private GlReset() {
    }

    public static void beforeVehicles() {
        GlStateManager.func_179138_g(OpenGlHelper.field_77478_a);
        GlStateManager.func_179138_g(OpenGlHelper.field_77476_b);
        GlStateManager.func_179098_w();
        GlStateManager.func_179090_x();
        GlStateManager.func_179138_g(OpenGlHelper.field_77478_a);
        GlStateManager.func_179090_x();
        GlStateManager.func_179098_w();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);

        GlStateManager.func_179127_m();
        GlStateManager.func_179106_n();
        GlStateManager.func_179147_l();
        GlStateManager.func_179084_k();
        GlStateManager.func_179097_i();
        GlStateManager.func_179126_j();
        GlStateManager.func_179132_a(false);
        GlStateManager.func_179132_a(true);
        GlStateManager.func_179129_p();
        GlStateManager.func_179089_o();
        GlStateManager.func_179118_c();
        GlStateManager.func_179141_d();

        GlStateManager.func_179145_e();
        GlStateManager.func_179140_f();
        for (int i = 0; i < 2; i++) {
            GlStateManager.func_179085_a(i);
            GlStateManager.func_179122_b(i);
        }
        GlStateManager.func_179142_g();
        GlStateManager.func_179119_h();
        // RenderHelper sets these next; a different cached value makes its calls reach GL
        GlStateManager.func_179104_a(GL11.GL_FRONT, GL11.GL_AMBIENT_AND_DIFFUSE);
        GlStateManager.func_179103_j(GL11.GL_SMOOTH);
        GlStateManager.func_179131_c(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager.func_179131_c(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
