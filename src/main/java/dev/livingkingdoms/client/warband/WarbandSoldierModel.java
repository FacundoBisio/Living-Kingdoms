package dev.livingkingdoms.client.warband;

import dev.livingkingdoms.citizenvisual.ArgonAnimationSet;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.monster.Vindicator;

/**
 * Warband soldier on the citizen rig. Walking, head tracking, riding and armor stay vanilla; the authored axe swing
 * replaces the vanilla swing while the entity's own attack animation runs, so it is timed by the real attack.
 */
final class WarbandSoldierModel extends PlayerModel<Vindicator> {
    private final ArgonAnimationSet.Clip axeAttack;

    WarbandSoldierModel(ModelPart root, ArgonAnimationSet.Clip axeAttack) {
        super(root, false);
        this.axeAttack = axeAttack;
    }

    @Override public void setupAnim(Vindicator soldier, float walk, float speed, float age, float yaw, float pitch) {
        // Vanilla never resets these axes; a previous pose must not leak into the next soldier.
        head.zRot = body.zRot = 0;
        crouching = soldier.isCrouching();
        boolean rightMain = soldier.getMainArm() == HumanoidArm.RIGHT;
        boolean main = !soldier.getMainHandItem().isEmpty(), off = !soldier.getOffhandItem().isEmpty();
        rightArmPose = (rightMain ? main : off) ? ArmPose.ITEM : ArmPose.EMPTY;
        leftArmPose = (rightMain ? off : main) ? ArmPose.ITEM : ArmPose.EMPTY;
        super.setupAnim(soldier, walk, speed, age, yaw, pitch);
        // The authored iron helm would sit inside a real helmet; show it only on a bare head.
        head.getChild("head_accessories").visible = soldier.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        if (!soldier.isPassenger()) {
            ModelPart workArm = rightMain ? rightArm : leftArm, supportArm = rightMain ? leftArm : rightArm;
            if (attackTime > 0 && axeAttack != null) {
                float seconds = Mth.clamp(attackTime, 0, 1) * axeAttack.lengthSeconds();
                apply(body, ArgonAnimationSet.Bone.BODY, seconds, rightMain, false);
                apply(head, ArgonAnimationSet.Bone.HEAD, seconds, rightMain, true);
                apply(workArm, ArgonAnimationSet.Bone.RIGHT_ARM, seconds, rightMain, false);
                apply(supportArm, ArgonAnimationSet.Bone.LEFT_ARM, seconds, rightMain, false);
            } else if (soldier.isAggressive()) {
                // Weapon raised while engaged, like the vanilla vindicator, with a small breathing sway.
                float sway = Mth.sin(age * .1F) * .04F;
                workArm.xRot = Math.min(workArm.xRot, -1.15F + sway);
                supportArm.xRot = Math.min(supportArm.xRot, -.5F - sway);
            }
        }
        hat.copyFrom(head);
        leftSleeve.copyFrom(leftArm); rightSleeve.copyFrom(rightArm); jacket.copyFrom(body);
        leftPants.copyFrom(leftLeg); rightPants.copyFrom(rightLeg);
    }

    /** Clip rotations are authored for a right-handed soldier; left-handed ones mirror the Y and Z axes. */
    private void apply(ModelPart part, ArgonAnimationSet.Bone bone, float seconds, boolean rightMain, boolean additive) {
        if (!axeAttack.hasTrack(bone, ArgonAnimationSet.Transform.ROTATION)) return;
        float mirror = rightMain ? 1 : -1;
        float x = axeAttack.sample(bone, ArgonAnimationSet.Transform.ROTATION, 0, seconds) * Mth.DEG_TO_RAD;
        float y = axeAttack.sample(bone, ArgonAnimationSet.Transform.ROTATION, 1, seconds) * Mth.DEG_TO_RAD * mirror;
        float z = axeAttack.sample(bone, ArgonAnimationSet.Transform.ROTATION, 2, seconds) * Mth.DEG_TO_RAD * mirror;
        if (additive) { part.xRot += x; part.yRot += y; part.zRot += z; }
        else { part.xRot = x; part.yRot = y; part.zRot = z; }
    }
}
