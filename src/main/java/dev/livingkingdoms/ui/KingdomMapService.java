package dev.livingkingdoms.ui;

import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.economy.BuildingDevelopment;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.rpg.EventService;
import dev.livingkingdoms.rpg.KingdomProgressService;
import dev.livingkingdoms.rpg.domain.KingdomStage;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Locale;

/**
 * Read-only kingdom map snapshot: territory, buildings with levels, construction sites, residents (loaded ones
 * with their live position and job), walls and the active event. The client paints the terrain itself from the
 * chunks it already has. Bounded: at most 256 buildings/sites and 256 residents per snapshot.
 */
public final class KingdomMapService {
    private KingdomMapService() {}

    public static boolean open(ServerPlayer player) {
        var settlement = target(player);
        if (settlement == null) { player.displayClientMessage(Component.translatable("ui.livingkingdoms.shortcut.no_settlement"), true); return false; }
        PacketDistributor.sendToPlayer(player, new UiPayloads.MapSnapshot(snapshot(player, settlement)));
        return true;
    }

    static Settlement target(ServerPlayer player) {
        var pos = player.blockPosition(); var dimension = player.level().dimension().location().toString();
        var data = SettlementSavedData.get(player.server);
        return data.alliedAt(dimension, pos.getX(), pos.getZ()).filter(s -> s.faction().isAllied())
                .or(() -> dev.livingkingdoms.settlement.NearestAlliedSettlementService.findNearest(player.serverLevel(), pos, 256))
                .filter(s -> s.territory().dimension().equals(dimension)).orElse(null);
    }

    public static CompoundTag snapshot(ServerPlayer player, Settlement settlement) {
        var server = player.server; var t = settlement.territory();
        var tag = new CompoundTag();
        tag.putString("name", VillageNames.display(settlement));
        tag.putInt("x", t.x()); tag.putInt("z", t.z()); tag.putInt("radius", Math.min(t.radius(), 128));
        int rank = KingdomProgressService.level(server, settlement.id());
        tag.putInt("rank", rank); tag.putString("stage", KingdomStage.of(rank).id());
        var buildings = new ListTag();
        for (var b : ProfessionSavedData.get(server).buildings(settlement.id())) {
            if (!b.active() || buildings.size() >= 256) continue;
            var row = new CompoundTag();
            row.putString("kind", b.kind().name().toLowerCase(Locale.ROOT));
            row.putInt("x0", b.bounds().minX()); row.putInt("z0", b.bounds().minZ()); row.putInt("x1", b.bounds().maxX()); row.putInt("z1", b.bounds().maxZ());
            row.putInt("level", BuildingDevelopment.level(server, settlement.id(), b.id()));
            buildings.add(row);
        }
        // Houses are not functional buildings: take them from the layout.
        SettlementSavedData.get(server).layout(settlement.id()).ifPresent(layout -> {
            for (var b : layout.buildings()) {
                if (buildings.size() >= 256) break;
                String kind = b.kind().name().toLowerCase(Locale.ROOT);
                if (!kind.startsWith("house")) continue;
                var row = new CompoundTag(); row.putString("kind", "house");
                row.putInt("x0", b.bounds().minX()); row.putInt("z0", b.bounds().minZ()); row.putInt("x1", b.bounds().maxX()); row.putInt("z1", b.bounds().maxZ());
                buildings.add(row);
            }
        });
        tag.put("buildings", buildings);
        var sites = new ListTag(); long now = server.overworld().getGameTime();
        for (var entry : ConstructionSavedData.get(server).projects(settlement.id())) {
            var p = entry.project();
            if (p.state() == ConstructionState.COMPLETED || sites.size() >= 64) continue;
            var bounds = entry.plan().buildings().getFirst().bounds();
            var row = new CompoundTag(); row.putString("kind", p.building().name().toLowerCase(Locale.ROOT));
            row.putInt("x0", bounds.minX()); row.putInt("z0", bounds.minZ()); row.putInt("x1", bounds.maxX()); row.putInt("z1", bounds.maxZ());
            row.putInt("percent", (int) Math.round(p.progress(now) * 100)); row.putString("state", p.state().name().toLowerCase(Locale.ROOT));
            sites.add(row);
        }
        tag.put("sites", sites);
        var people = new ListTag(); var jobs = ProfessionSavedData.get(server); var rpg = RpgSavedData.get(server);
        for (var c : CitizenSavedData.get(server).citizens(settlement.id())) {
            if (c.state() != CitizenState.ACTIVE || people.size() >= 256) continue;
            var row = new CompoundTag(); row.putString("name", c.name());
            var job = jobs.profession(c.id()).filter(p -> p.active()).orElse(null);
            row.putString("profession", c.role() == CitizenRole.MAYOR ? "mayor" : job == null ? "unassigned" : job.type().name().toLowerCase(Locale.ROOT));
            if (job != null) row.putString("work", job.workState().name().toLowerCase(Locale.ROOT));
            var trip = rpg.expedition(c.id());
            if (trip.isPresent()) { row.putBoolean("away", true); row.putLong("back", trip.get().remaining(now) / 20); }
            else if (player.serverLevel().getEntity(c.entityId()) instanceof Villager v) { row.putInt("px", v.getBlockX()); row.putInt("pz", v.getBlockZ()); }
            people.add(row);
        }
        tag.put("citizens", people);
        rpg.wall(settlement.id()).ifPresent(w -> { if (w.tier() > 0) { tag.putInt("wall_tier", w.tier()); tag.putInt("wall_radius", w.radius()); tag.putBoolean("wall_done", w.complete()); } });
        var event = EventService.hud(player, settlement.id());
        EventService.treasureHint(settlement.id()).ifPresent(hint -> { event.putInt("tx", hint.getX()); event.putInt("tz", hint.getZ()); });
        if (!event.isEmpty()) tag.put("event", event);
        tag.putInt("px", player.getBlockX()); tag.putInt("pz", player.getBlockZ()); tag.putFloat("yaw", player.getYRot());
        return tag;
    }
}
