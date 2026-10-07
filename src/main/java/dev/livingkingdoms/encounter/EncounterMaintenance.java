package dev.livingkingdoms.encounter;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.stream.Collectors;

/** Low-frequency bounded metadata maintenance, never physical simulation in unloaded chunks. */
public final class EncounterMaintenance {
    private EncounterMaintenance() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().overworld().getGameTime() % 1200 != 0) return;
        cleanup(event.getServer());
    }

    public static void cleanup(MinecraftServer server) {
        EncounterSavedData data = EncounterSavedData.get(server);
        QuestSavedData reputation = QuestSavedData.get(server);
        var removed = data.cleanup(server.overworld().getGameTime(),
                KingdomConfig.ENCOUNTER_COMPLETED_RETENTION.get(), KingdomConfig.ENCOUNTER_ACTIVE_LIFETIME.get());
        for (var party : removed) {
            // Direct UUID lookup in currently loaded dimensions. No chunks or entity lists are fetched.
            for (var level : server.getAllLevels()) {
                for (var member : party.remainingMembers()) {
                    Entity entity = level.getEntity(member);
                    if (entity != null && EncounterMember.read(entity).filter(tag -> tag.partyId().equals(party.id())).isPresent()) entity.discard();
                }
            }
        }
        reputation.retainEncounterReceipts(data.parties().stream().map(party -> party.id()).collect(Collectors.toSet()));
        if (!removed.isEmpty()) LogUtils.getLogger().debug("Retired {} Living Kingdoms encounter records", removed.size());
    }
}
