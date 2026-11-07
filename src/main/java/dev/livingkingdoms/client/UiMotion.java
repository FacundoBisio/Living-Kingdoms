package dev.livingkingdoms.client;

import net.minecraft.Util;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Shared open/transition motion for every Living Kingdoms screen: a short ease-out slide and fade.
 * Implemented around the screen's own render pass (pose push in Pre, pop + curtain in Post) so each
 * screen keeps its layout code untouched. Mouse hit-testing is unaffected after the ~200 ms motion.
 */
public final class UiMotion {
    static final long DURATION_MS = 200;
    private UiMotion() {}

    static float progress(long startedAt) {
        float t = Math.clamp((Util.getMillis() - startedAt) / (float) DURATION_MS, 0F, 1F);
        return 1F - (1F - t) * (1F - t) * (1F - t);
    }

    public static void pre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof KingdomScreen screen)) return;
        float p = progress(screen.transitionStartedAt());
        var pose = event.getGuiGraphics().pose();
        pose.pushPose();
        if (p < 1F) {
            float scale = 0.97F + 0.03F * p;
            float cx = screen.width / 2F, cy = screen.height / 2F;
            pose.translate(cx, cy + (1F - p) * 10F, 0);
            pose.scale(scale, scale, 1F);
            pose.translate(-cx, -cy, 0);
        }
    }

    public static void post(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof KingdomScreen screen)) return;
        var graphics = event.getGuiGraphics();
        graphics.pose().popPose();
        float p = progress(screen.transitionStartedAt());
        if (p < 1F) {
            int alpha = (int) ((1F - p) * 200F);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            graphics.fill(0, 0, screen.width, screen.height, (alpha << 24) | 0x0C151B);
            graphics.pose().popPose();
        }
    }
}
