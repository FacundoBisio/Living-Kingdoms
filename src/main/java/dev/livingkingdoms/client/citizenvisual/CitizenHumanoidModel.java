package dev.livingkingdoms.client.citizenvisual;

import dev.livingkingdoms.citizenvisual.CitizenPresentation;
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
    CitizenHumanoidModel(ModelPart root) { super(root, false); }

    @Override public void setupAnim(Villager npc, float walk, float speed, float age, float yaw, float pitch) {
        crouching = npc.isCrouching();
        boolean mainItem = !npc.getMainHandItem().isEmpty() || presentation != null && presentation.showsProfessionTool();
        boolean offItem = !npc.getOffhandItem().isEmpty();
        boolean rightMain = npc.getMainArm() == HumanoidArm.RIGHT;
        rightArmPose = (rightMain ? mainItem : offItem) ? ArmPose.ITEM : ArmPose.EMPTY;
        leftArmPose = (rightMain ? offItem : mainItem) ? ArmPose.ITEM : ArmPose.EMPTY;
        super.setupAnim(npc, walk, speed, age, yaw, pitch);
        if (npc.isSleeping()) {
            // LivingEntityRenderer applies the native bed orientation; limbs rest without idle arm bobbing.
            head.xRot = head.yRot = head.zRot = body.xRot = body.yRot = body.zRot = 0;
            rightArm.xRot = rightArm.yRot = rightArm.zRot = leftArm.xRot = leftArm.yRot = leftArm.zRot = 0;
            rightLeg.xRot = rightLeg.yRot = rightLeg.zRot = leftLeg.xRot = leftLeg.yRot = leftLeg.zRot = 0;
            hat.copyFrom(head); copyOverlays(); return;
        }
        if (presentation == null || !CitizenVisualConfig.WORK_ANIMATIONS.get() || npc.isPassenger()) return;
        float elapsed = npc.level().getGameTime() - actionAt + (age - npc.tickCount);
        // Native swing packets still animate real attacks/tools. XP pulse covers work between snapshot ticks.
        if (presentation.active() && !presentation.travelling() && speed < .1F && elapsed >= 0 && elapsed < 10
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
        if (presentation.state() == WorkState.ENGAGING) leftArm.xRot = Math.min(leftArm.xRot, -.8F);
        copyOverlays();
    }
    private void copyOverlays() {
        leftSleeve.copyFrom(leftArm); rightSleeve.copyFrom(rightArm); jacket.copyFrom(body);
        leftPants.copyFrom(leftLeg); rightPants.copyFrom(rightLeg);
    }
}
