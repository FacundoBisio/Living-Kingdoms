package dev.livingkingdoms.rpg;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.rpg.domain.RefinementRules;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import java.util.ArrayList;

/**
 * Refinement lives entirely on the item stack (custom data + attribute modifier + lore), so it needs no
 * SavedData, survives trades/chests, and other mods see a normal attribute-modified weapon.
 */
public final class RefinementService {
    public static final String LEVEL_KEY = "livingkingdoms:refinement";
    public static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "refinement");
    public static final ResourceLocation TOUGHNESS_ID = ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "refinement_toughness");
    private static final String LORE_KEY = "item.livingkingdoms.refinement.lore";
    private RefinementService() {}

    public static boolean refinable(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1 && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
                || stack.is(Items.TRIDENT) || stack.is(Items.MACE) || armor(stack));
    }

    public static boolean armor(ItemStack stack) { return stack.getItem() instanceof net.minecraft.world.item.ArmorItem; }

    public static int level(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0 : RefinementRules.clamp(data.copyTag().getInt(LEVEL_KEY));
    }

    /** Applies an absolute refinement level, replacing any earlier refinement modifier and lore line. */
    public static ItemStack apply(ItemStack stack, int level) {
        if (!refinable(stack)) throw new IllegalArgumentException("Item cannot be refined");
        int value = RefinementRules.clamp(level);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(LEVEL_KEY, value));
        // Armor keeps its stats in the item's defaults, not in the component: start from the effective modifiers.
        var base = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (base.modifiers().isEmpty()) base = stack.getItem().getDefaultAttributeModifiers(stack);
        var builder = ItemAttributeModifiers.builder();
        for (var entry : base.modifiers()) if (!entry.modifier().id().equals(MODIFIER_ID) && !entry.modifier().id().equals(TOUGHNESS_ID))
            builder.add(entry.attribute(), entry.modifier(), entry.slot());
        if (value > 0 && stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor) {
            var slot = EquipmentSlotGroup.bySlot(armor.getEquipmentSlot());
            builder.add(Attributes.ARMOR, new AttributeModifier(MODIFIER_ID, RefinementRules.armorBonus(value), AttributeModifier.Operation.ADD_VALUE), slot);
            builder.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(TOUGHNESS_ID, RefinementRules.toughnessBonus(value), AttributeModifier.Operation.ADD_VALUE), slot);
        } else if (value > 0) builder.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(MODIFIER_ID, RefinementRules.damageBonus(value),
                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build().withTooltip(base.showInTooltip()));
        var lines = new ArrayList<Component>();
        for (var line : stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines())
            if (!(line.getContents() instanceof TranslatableContents t && t.getKey().equals(LORE_KEY))) lines.add(line);
        if (value > 0) lines.addFirst(Component.translatable(LORE_KEY, value, RefinementRules.MAX_LEVEL)
                .withStyle(value >= RefinementRules.MAX_LEVEL ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, new ItemLore(lines));
        if (value >= RefinementRules.MAX_LEVEL) stack.set(DataComponents.RARITY, Rarity.EPIC);
        else if (value >= 3) stack.set(DataComponents.RARITY, Rarity.RARE);
        return stack;
    }
}
