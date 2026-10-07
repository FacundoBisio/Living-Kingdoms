package dev.livingkingdoms.progression;

import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.progression.domain.StatScaling;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Objects;
import java.util.Optional;

/** Entity level and frozen stat policy. Permanent modifiers have stable IDs and never stack on reload. */
public final class EntityProgression {
    public static final String DATA_KEY = "livingkingdoms:progression";
    private EntityProgression() {}
    public record Profile(LevelValue level, boolean elite, StatScaling stats) {
        public Profile { Objects.requireNonNull(level); Objects.requireNonNull(stats); }
        public static Profile legacy() { return new Profile(new LevelValue(1), false, StatScaling.vanilla()); }
    }

    public static Optional<Profile> read(Mob mob) {
        var root = mob.getPersistentData();
        if (!root.contains(DATA_KEY)) return Optional.empty();
        require(root, DATA_KEY, Tag.TAG_COMPOUND);
        var tag = root.getCompound(DATA_KEY);
        require(tag, "schema", Tag.TAG_INT); require(tag, "level", Tag.TAG_INT); require(tag, "elite", Tag.TAG_BYTE);
        if (tag.getInt("schema") != 1 || (tag.getByte("elite") != 0 && tag.getByte("elite") != 1)) throw new IllegalArgumentException("Invalid entity progression schema/elite");
        require(tag, "stats", Tag.TAG_COMPOUND);
        var stats = tag.getCompound("stats");
        for (String field : new String[]{"health", "damage", "armor", "speed"}) require(stats, field, Tag.TAG_DOUBLE);
        return Optional.of(new Profile(new LevelValue(tag.getInt("level")), tag.getBoolean("elite"),
                new StatScaling(stats.getDouble("health"), stats.getDouble("damage"), stats.getDouble("armor"), stats.getDouble("speed"))));
    }

    /** New spawn only: stats are applied after vanilla initialization, then filled to their new maximum. */
    public static void initializeSpawn(Mob mob, Profile profile) {
        authority(mob);
        if (read(mob).filter(existing -> !existing.equals(profile)).isPresent()) throw new IllegalStateException("Progression is already assigned");
        write(mob, profile);
        applyStats(mob, profile.stats());
        mob.setHealth(mob.getMaxHealth());
    }

    /** Legacy members stay level 1/vanilla; reload does not reroll, re-equip, or heal them. */
    public static Profile restore(Mob mob) {
        authority(mob);
        var existing = read(mob);
        Profile profile = existing.orElseGet(Profile::legacy);
        if (existing.isEmpty()) write(mob, profile);
        float health = mob.getHealth();
        applyStats(mob, profile.stats());
        mob.setHealth(Math.min(health, mob.getMaxHealth()));
        return profile;
    }

    public static void copyConversion(Mob before, Mob after) {
        authority(after);
        Profile profile = read(before).orElseGet(Profile::legacy);
        write(after, profile);
        float health = after.getHealth();
        applyStats(after, profile.stats());
        after.setHealth(Math.min(health, after.getMaxHealth()));
    }

    private static void applyStats(Mob mob, StatScaling stats) {
        modifier(mob, Attributes.MAX_HEALTH, "level_health", stats.healthBonus(), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modifier(mob, Attributes.ATTACK_DAMAGE, "level_damage", stats.damageBonus(), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modifier(mob, Attributes.ARMOR, "level_armor", stats.armorBonus(), AttributeModifier.Operation.ADD_VALUE);
        modifier(mob, Attributes.MOVEMENT_SPEED, "level_speed", stats.speedBonus(), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }
    private static void modifier(Mob mob, Holder<Attribute> attribute, String name, double amount, AttributeModifier.Operation operation) {
        var instance = mob.getAttribute(attribute);
        if (instance == null) return;
        var id = ResourceLocation.fromNamespaceAndPath("livingkingdoms", name);
        instance.removeModifier(id);
        if (amount != 0) instance.addPermanentModifier(new AttributeModifier(id, amount, operation));
    }
    private static void write(Mob mob, Profile profile) {
        CompoundTag tag = new CompoundTag(), stats = new CompoundTag();
        tag.putInt("schema", 1); tag.putInt("level", profile.level().value()); tag.putBoolean("elite", profile.elite());
        stats.putDouble("health", profile.stats().healthBonus()); stats.putDouble("damage", profile.stats().damageBonus());
        stats.putDouble("armor", profile.stats().armorBonus()); stats.putDouble("speed", profile.stats().speedBonus());
        tag.put("stats", stats); mob.getPersistentData().put(DATA_KEY, tag);
    }
    private static void require(CompoundTag tag, String field, int type) {
        if (!tag.contains(field, type)) throw new IllegalArgumentException("Missing/invalid progression field: " + field);
    }
    private static void authority(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) throw new IllegalStateException("Progression requires the server thread");
        if (EncounterMember.read(mob).isEmpty()) throw new IllegalArgumentException("Hostile scaling requires a managed encounter member");
    }
}
