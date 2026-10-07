package dev.livingkingdoms.npc;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Gameplay roles, independent of vanilla trading professions. Only the Mayor is active in M2. */
public enum NpcRole {
    MAYOR,
    BLACKSMITH,
    GUARD;

    public Component displayName() {
        return Component.translatable("role.livingkingdoms." + name().toLowerCase(Locale.ROOT));
    }
}
