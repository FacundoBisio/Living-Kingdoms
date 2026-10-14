package dev.livingkingdoms.client.citizenvisual;

import dev.livingkingdoms.citizenvisual.CitizenPresentation;
import dev.livingkingdoms.citizenvisual.ArgonAnimationSet;
import dev.livingkingdoms.citizenvisual.ArgonActivity;
import dev.livingkingdoms.profession.domain.WorkState;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.Villager;

/** Vanilla independent limbs, walking, riding and attack animation; short confirmed-work accents. */
final class CitizenHumanoidModel extends PlayerModel<Villager> {
    CitizenPresentation presentation;
    long actionAt = -1000;
    private final ArgonAnimationSet animations;
    CitizenHumanoidModel(ModelPart root) { this(root, null); }
    CitizenHumanoidModel(ModelPart root, ArgonAnimationSet animations) { super(root, false); this.animations = animations; }
    boolean argon() { return animations != null; }

    @Override public void setupAnim(Villager npc, float walk, float speed, float age, float yaw, float pitch) {
        // Vanilla does not reset these axes. Shared models must not leak a custom pack's pose into another citizen.
        head.zRot = body.zRot = 0;
        crouching = npc.isCrouching();
        boolean mainItem = !npc.getMainHandItem().isEmpty() || presentation != null && presentation.showsProfessionTool();
        boolean offItem = !npc.getOffhandItem().isEmpty();
        boolean rightMain = npc.getMainArm() == HumanoidArm.RIGHT;
        rightArmPose = (rightMain ? mainItem : offItem) ? ArmPose.ITEM : ArmPose.EMPTY;
        leftArmPose = (rightMain ? offItem : mainItem) ? ArmPose.ITEM : ArmPose.EMPTY;
        super.setupAnim(npc, walk, speed, age, yaw, pitch);
        if (argon() && presentation != null) {
            int variant = presentation.skinVariant();
            head.getChild("beard").visible = variant == 1 || variant == 4 || variant == 7;
            head.getChild("head_accessories").visible = presentation.profession() == dev.livingkingdoms.profession.domain.ProfessionType.MAYOR;
        }
        if (npc.isSleeping()) {
            // LivingEntityRenderer applies the native bed orientation; limbs rest without idle arm bobbing.
            head.xRot = head.yRot = head.zRot = body.xRot = body.yRot = body.zRot = 0;
            rightArm.xRot = rightArm.yRot = rightArm.zRot = leftArm.xRot = leftArm.yRot = leftArm.zRot = 0;
            rightLeg.xRot = rightLeg.yRot = rightLeg.zRot = leftLeg.xRot = leftLeg.yRot = leftLeg.zRot = 0;
            hat.copyFrom(head); copyOverlays(); return;
        }
        if (presentation == null || !CitizenVisualConfig.WORK_ANIMATIONS.get() || npc.isPassenger()) return;
        float elapsed = npc.level().getGameTime() - actionAt + (age - npc.tickCount);
        if (argon() && applyArgon(npc, elapsed, speed, age, rightMain)) { copyOverlays(); return; }
        // Native swing packets still animate real attacks/tools. XP pulse covers work between snapshot ticks.
        if (presentation.active() && !presentation.travelling() && attackTime <= 0 && speed < .1F && elapsed >= 0 && elapsed < 10
                && (presentation.state() == WorkState.WORKING || presentation.state() == WorkState.REPAIRING)) {
            float strike = Mth.sin(elapsed / 10F * Mth.PI);
            ModelPart workingArm = rightMain ? rightArm : leftArm;
            ModelPart supportingArm = rightMain ? leftArm : rightArm;
            switch (presentation.profession()) {
                case FARMER -> { workingArm.xRot = -.7F - strike; body.xRot = .12F * strike; }
                case LUMBERJACK -> { workingArm.xRot = -1.1F - strike; workingArm.zRot = rightMain ? -.25F : .25F; }
                case MINER, BUILDER, BLACKSMITH -> { workingArm.xRot = -.8F - 1.4F * strike; supportingArm.xRot = -.4F; }
                case MERCHANT -> { workingArm.xRot = -.9F; supportingArm.xRot = -.7F - .2F * strike; }
                default -> { }
            }
        }
        if (presentation.state() == WorkState.ENGAGING && attackTime <= 0
                && npc.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) {
            ModelPart shieldArm = rightMain ? leftArm : rightArm;
            shieldArm.xRot = Math.min(shieldArm.xRot, -.8F);
        }
        copyOverlays();
    }
    private boolean applyArgon(Villager npc, float elapsed, float speed, float age, boolean rightMain) {
        String work = ArgonActivity.workClip(presentation, elapsed, speed, npc.isSleeping(), npc.isPassenger(), attackTime > 0);
        if (work != null) {
            var clip = animations.clip(switch (work) {
                case "work_hammer" -> "animation.citizen.work_hammer";
                case "farm_plant" -> "animation.citizen.farm_plant";
                case "lumberjack_chop" -> "animation.citizen.lumberjack_chop";
                default -> throw new IllegalArgumentException("Unknown confirmed work clip");
            });
            if (elapsed >= clip.lengthSeconds() * 20F) return false;
            float seconds = elapsed / 20F;
            // Fade the authored posture back to vanilla instead of snapping at the ends of a work pulse.
            float weight = Mth.clamp(Math.min(elapsed / 3F, (clip.lengthSeconds() * 20F - elapsed) / 4F), 0, 1);
            apply(body, clip, ArgonAnimationSet.Bone.BODY, seconds, weight, false, true);
            apply(head, clip, ArgonAnimationSet.Bone.HEAD, seconds, weight, false, true);
            apply(rightMain ? rightArm : leftArm, clip, ArgonAnimationSet.Bone.RIGHT_ARM, seconds, weight, !rightMain, false);
            apply(rightMain ? leftArm : rightArm, clip, ArgonAnimationSet.Bone.LEFT_ARM, seconds, weight, !rightMain, false);
            return true;
        }
        // Native walk/sprint/swing/sleep/riding keep correct real movement and item/armor behavior.
        // Only a genuine active-use shield can request the authored blocking pose.
        if (npc.isBlocking()) {
            var clip = animations.clip("animation.citizen.guard_block");
            boolean offhandShield = npc.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND;
            boolean leftShield = offhandShield == rightMain;
            apply(leftShield ? leftArm : rightArm, clip, ArgonAnimationSet.Bone.LEFT_ARM, 0, 1, !leftShield, false);
        } else if (presentation.active() && !presentation.travelling() && speed < .1F && attackTime <= 0
                && (presentation.state() == WorkState.IDLE || presentation.state() == WorkState.WORKING
                    || presentation.state() == WorkState.REPAIRING || presentation.state() == WorkState.PATROLLING)) {
            var clip = animations.clip("animation.citizen.idle");
            apply(head, clip, ArgonAnimationSet.Bone.HEAD, age / 20F, .4F, false, true);
        }
        return false;
    }
    private static void apply(ModelPart part, ArgonAnimationSet.Clip clip, ArgonAnimationSet.Bone bone,
            float seconds, float weight, boolean mirrored, boolean additive) {
        if (!clip.hasTrack(bone, ArgonAnimationSet.Transform.ROTATION)) return;
        float x = clip.sample(bone, ArgonAnimationSet.Transform.ROTATION, 0, seconds) * Mth.DEG_TO_RAD;
        float y = clip.sample(bone, ArgonAnimationSet.Transform.ROTATION, 1, seconds) * Mth.DEG_TO_RAD * (mirrored ? -1 : 1);
        float z = clip.sample(bone, ArgonAnimationSet.Transform.ROTATION, 2, seconds) * Mth.DEG_TO_RAD * (mirrored ? -1 : 1);
        if (additive) { part.xRot += x * weight; part.yRot += y * weight; part.zRot += z * weight; }
        else { part.xRot = Mth.lerp(weight, part.xRot, x); part.yRot = Mth.lerp(weight, part.yRot, y); part.zRot = Mth.lerp(weight, part.zRot, z); }
    }
    private void copyOverlays() {
        hat.copyFrom(head);
        leftSleeve.copyFrom(leftArm); rightSleeve.copyFrom(rightArm); jacket.copyFrom(body);
        leftPants.copyFrom(leftLeg); rightPants.copyFrom(rightLeg);
    }
}
