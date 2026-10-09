package dev.livingkingdoms.profession;

import dev.livingkingdoms.config.GuardConfig;
import dev.livingkingdoms.profession.domain.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import java.util.UUID;

/** Entity equipment persists in vanilla NBT. Only service-issued slots are changed or removed. */
public final class GuardEquipment {
    private static final String PICKUP="livingkingdoms:guard_original_pickup";
    private static final String OWNER="livingkingdoms:guard_equipment";
    private static final ResourceLocation HEALTH=ResourceLocation.parse("livingkingdoms:guard_health");
    private GuardEquipment() {}
    public static boolean issued(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().hasUUID(OWNER); }
    private static boolean owned(ItemStack stack,UUID citizen) {
        var t=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag(); return t.hasUUID(OWNER) && t.getUUID(OWNER).equals(citizen);
    }
    public static void apply(Villager v,Profession p) {
        if(!(v.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) throw new IllegalStateException("Guard equipment requires server authority");
        if(!v.getPersistentData().contains(PICKUP)) v.getPersistentData().putBoolean(PICKUP,v.canPickUpLoot());
        v.setCanPickUpLoot(false);
        int tier=GuardPolicy.equipmentTier(p.level().value(),GuardConfig.IRON_ARMOR_LEVEL.get());
        equip(v,p,EquipmentSlot.MAINHAND,Items.IRON_SWORD);
        equip(v,p,EquipmentSlot.OFFHAND,Items.SHIELD);
        equip(v,p,EquipmentSlot.CHEST,tier==2?Items.IRON_CHESTPLATE:Items.CHAINMAIL_CHESTPLATE);
        equip(v,p,EquipmentSlot.HEAD,tier==2?Items.IRON_HELMET:Items.LEATHER_HELMET);
        if(tier==2) { equip(v,p,EquipmentSlot.LEGS,Items.IRON_LEGGINGS); equip(v,p,EquipmentSlot.FEET,Items.IRON_BOOTS); }
        var armor=v.getItemBySlot(EquipmentSlot.CHEST);
        double roll=(Math.floorMod(p.citizenId().hashCode(),10000))/10000.0;
        if(owned(armor,p.citizenId()) && p.level().value()>=GuardConfig.ENCHANT_LEVEL.get() && roll<GuardConfig.ENCHANT_CHANCE.get() && !armor.isEnchanted())
            armor.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION),1);
        var health=v.getAttribute(Attributes.MAX_HEALTH);
        if(health!=null) {
            double bonus=Math.min(8,(p.level().value()-1)*GuardConfig.HEALTH_PER_LEVEL.get());
            var existing=health.getModifier(HEALTH);
            if(existing==null || existing.amount()!=bonus) {
                health.removeModifier(HEALTH); if(bonus>0) health.addPermanentModifier(new AttributeModifier(HEALTH,bonus,AttributeModifier.Operation.ADD_VALUE));
                v.setHealth(Math.min(v.getHealth(),v.getMaxHealth()));
            }
        }
    }
    private static void equip(Villager v,Profession p,EquipmentSlot slot,Item item) {
        var old=v.getItemBySlot(slot);
        if(!old.isEmpty() && (!owned(old,p.citizenId()) || old.is(item))) return;
        var stack=new ItemStack(item); var tag=new CompoundTag(); tag.putUUID(OWNER,p.citizenId());
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag)); v.setItemSlot(slot,stack); v.setDropChance(slot,0);
        // Pick-up is disabled while equipped; ordinary trades and inventory remain untouched.
    }
    public static void remove(Villager v) {
        if(v.getPersistentData().contains(PICKUP)) { v.setCanPickUpLoot(v.getPersistentData().getBoolean(PICKUP)); v.getPersistentData().remove(PICKUP); }
        for(var slot:EquipmentSlot.values()) if(issued(v.getItemBySlot(slot))) { v.setItemSlot(slot,ItemStack.EMPTY); v.setDropChance(slot,net.minecraft.world.entity.Mob.DEFAULT_EQUIPMENT_DROP_CHANCE); }
        var health=v.getAttribute(Attributes.MAX_HEALTH); if(health!=null) health.removeModifier(HEALTH);
        v.setHealth(Math.min(v.getHealth(),v.getMaxHealth()));
    }
}
