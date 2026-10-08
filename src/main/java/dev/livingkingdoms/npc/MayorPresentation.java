package dev.livingkingdoms.npc;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.List;
import java.util.UUID;

/** Deterministic defaults also migrate pre-presentation Mayors when their chunk loads. */
public final class MayorPresentation {
    public static final List<String> NAMES = List.of("Aldric", "Rowan", "Cedric", "Elric", "Mira", "Isolde", "Edric", "Bryn", "Alina", "Oswin", "Freya", "Leofric");
    private static final String NAME_KEY = "livingkingdoms:mayor_name";
    private MayorPresentation() {}

    public static String name(UUID id) {
        return NAMES.get(Math.floorMod(id.getMostSignificantBits() ^ id.getLeastSignificantBits(), NAMES.size()));
    }

    public static String name(Villager mayor) {
        String saved = mayor.getPersistentData().getString(NAME_KEY);
        return NAMES.contains(saved) ? saved : name(mayor.getUUID());
    }

    public static void apply(Villager mayor) {
        var tag = mayor.getPersistentData();
        String name = tag.getString(NAME_KEY);
        if (!NAMES.contains(name)) { name = name(mayor.getUUID()); tag.putString(NAME_KEY, name); }
        mayor.setCustomName(Component.translatable("npc.livingkingdoms.mayor.name", name));
        mayor.setCustomNameVisible(true);
        mayor.setNoAi(true);
        mayor.setPersistenceRequired();
        // The ceremonial robe is vanilla; a client-only geometry layer supplies the gold circlet.
        mayor.setVillagerData(mayor.getVillagerData().setProfession(VillagerProfession.CLERIC));
        mayor.setOffers(new net.minecraft.world.item.trading.MerchantOffers());
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof Villager villager
                && NpcIdentity.read(villager).filter(id -> id.role() == NpcRole.MAYOR).isPresent()) apply(villager);
    }
}
