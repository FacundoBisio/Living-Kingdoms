package dev.livingkingdoms.citizen;

import dev.livingkingdoms.citizen.domain.Citizen;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.domain.Housing;
import dev.livingkingdoms.citizen.domain.HousingStatus;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.CitizenConfig;
import dev.livingkingdoms.npc.MayorPresentation;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementOrigin;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Event/request driven migration and vanilla entity presentation. Never recreates a missing citizen. */
public final class CitizenService {
    public static final String CITIZEN_ID_KEY = "livingkingdoms:citizen_id";
    private static final int[][] SPAWN_OFFSETS = {
            {0,0}, {1,0}, {-1,0}, {0,1}, {0,-1}, {1,1}, {-1,1}, {1,-1}, {-1,-1},
            {2,0}, {-2,0}, {0,2}, {0,-2}, {2,2}, {-2,2}, {2,-2}, {-2,-2}
    };

    private CitizenService() {}

    /** Legacy operator-created metadata has no physical community to migrate; real settlements use citizen identities. */
    public static int population(MinecraftServer server, Settlement settlement) {
        var data = CitizenSavedData.get(server);
        boolean metadataOnly = settlement.provenance().origin()==SettlementOrigin.GENERATED
                && SettlementSavedData.get(server).layout(settlement.id()).isEmpty()
                && QuestSavedData.get(server).mayor(settlement.id()).isEmpty()
                && data.citizens(settlement.id()).isEmpty();
        return metadataOnly ? settlement.population() : data.population(settlement.id());
    }

    public static void ensureInitialized(ServerLevel level, Settlement settlement) {
        validate(level, settlement);
        var data = CitizenSavedData.get(level.getServer());
        synchronizeHousing(level, settlement);
        if (!data.initialized(settlement.id())) {
            migrateMayorAndReceipts(level, settlement);
            // A bounded, one-time query catches old loaded residents; later chunk joins use their NPC identity.
            var residents = loadedVillagers(level, settlement);
            if (settlement.provenance().origin() == SettlementOrigin.CONVERTED) {
                residents.forEach(villager -> registerConverted(level, settlement, villager));
            } else {
                residents.forEach(villager -> registerManaged(level, settlement, villager));
            }
            data.markInitialized(settlement.id());
        }
        refreshHomes(level, settlement);
    }

    /** Called only after conversion's placement/NPC transaction has successfully committed. */
    public static void initializeConverted(ServerLevel level, Settlement settlement, List<Villager> candidates) {
        validate(level, settlement);
        var data = CitizenSavedData.get(level.getServer());
        if (data.initialized(settlement.id())) { ensureInitialized(level, settlement); return; }
        synchronizeHousing(level, settlement);
        migrateMayorAndReceipts(level, settlement);
        candidates.stream().sorted(Comparator.comparing(Villager::getUUID))
                .forEach(villager -> registerConverted(level, settlement, villager));
        data.markInitialized(settlement.id());
        refreshHomes(level, settlement);
    }

    private static void synchronizeHousing(ServerLevel level, Settlement settlement) {
        SettlementSavedData.get(level.getServer()).layout(settlement.id()).ifPresent(layout ->
                CitizenSavedData.get(level.getServer()).synchronizeHousing(settlement, layout,
                        CitizenConfig::capacity, CitizenConfig.INCLUDE_TEMPORARY_SHELTERS.get()));
    }

    /** Only UUID lookups of this settlement's registered citizens; unloaded entities stay ACTIVE. */
    public static void refreshHomes(ServerLevel level, Settlement settlement) {
        validate(level, settlement);
        var data = CitizenSavedData.get(level.getServer());
        data.assignHomes(settlement.id());
        for (Citizen citizen : data.citizens(settlement.id())) {
            if (citizen.state() == CitizenState.ACTIVE && level.getEntity(citizen.entityId()) instanceof Villager villager
                    && canApply(citizen,villager))
                apply(citizen, villager);
        }
    }

    private static void migrateMayorAndReceipts(ServerLevel level, Settlement settlement) {
        var data = CitizenSavedData.get(level.getServer());
        QuestSavedData.get(level.getServer()).mayor(settlement.id()).ifPresent(entityId -> {
            var entity = level.getEntity(entityId);
            if (entity != null && (!(entity instanceof Villager mayor) || !mayor.isAlive() || !isManaged(mayor,settlement))) return;
            if (entity instanceof Villager mayor && data.byEntity(entityId).isEmpty()
                    && mayor.getPersistentData().contains(CITIZEN_ID_KEY)) return;
            String name = entity instanceof Villager mayor ? MayorPresentation.name(mayor) : MayorPresentation.name(entityId);
            registerIdentity(level, settlement, entityId, name, CitizenRole.MAYOR);
            if (!(entity instanceof Villager mayor) || !isManaged(mayor, settlement)) return;
            for (int index = 0; index < 2; index++) {
                String receipt = "livingkingdoms:founding_resident_" + index;
                if (!mayor.getPersistentData().hasUUID(receipt)) continue;
                UUID residentId = mayor.getPersistentData().getUUID(receipt);
                var resident = level.getEntity(residentId);
                // A receipt is durable proof of identity even when the resident's chunk is unloaded.
                if (resident == null) registerIdentity(level, settlement, residentId, CitizenNames.forIdentity(residentId), CitizenRole.UNASSIGNED);
                else if (resident instanceof Villager villager) registerManaged(level, settlement, villager);
            }
            data.byEntity(entityId).filter(c -> canApply(c,mayor)).ifPresent(c -> apply(c, mayor));
        });
    }

    private static List<Villager> loadedVillagers(ServerLevel level, Settlement settlement) {
        var t = settlement.territory();
        return level.getEntitiesOfClass(Villager.class,
                new AABB(t.x()-t.radius(), level.getMinBuildHeight(), t.z()-t.radius(),
                        t.x()+t.radius()+1, level.getMaxBuildHeight(), t.z()+t.radius()+1),
                villager -> inTerritory(level, settlement, villager)).stream()
                .sorted(Comparator.comparing(Villager::getUUID)).toList();
    }

    private static void registerConverted(ServerLevel level, Settlement settlement, Villager villager) {
        if (!inTerritory(level, settlement, villager) || !villager.isAlive() || villager.isBaby()) return;
        if (isManaged(villager, settlement)) { registerManaged(level, settlement, villager); return; }
        // Preserve every reserved/malformed identity and never take another settlement's NPC.
        if (!NpcIdentity.isUnassigned(villager) || villager.getPersistentData().contains(CITIZEN_ID_KEY)
                || CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID()).isPresent()) return;
        // Conversion records membership independently, preserving a vanilla trader's NPC/trade identity.
        registerIdentity(level,settlement,villager.getUUID(),residentName(villager),CitizenRole.UNASSIGNED);
        CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID())
                .filter(c -> canApply(c,villager)).ifPresent(c -> apply(c,villager));
    }

    /** Join events can safely register previously unloaded, already tagged founding residents. */
    public static void registerManaged(ServerLevel level, Settlement settlement, Villager villager) {
        if (villager.level() != level || !villager.isAlive() || villager.isBaby() || !isManaged(villager, settlement)) return;
        if (CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID()).isEmpty()
                && villager.getPersistentData().contains(CITIZEN_ID_KEY)) return;
        var role = NpcIdentity.read(villager).orElseThrow().role() == NpcRole.MAYOR
                ? CitizenRole.MAYOR : CitizenRole.UNASSIGNED;
        String name = role == CitizenRole.MAYOR ? MayorPresentation.name(villager) : residentName(villager);
        registerIdentity(level, settlement, villager.getUUID(), name, role);
        CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID())
                .filter(c -> c.settlementId().equals(settlement.id()) && canApply(c,villager))
                .ifPresent(c -> apply(c, villager));
    }

    private static String residentName(Villager villager) {
        if (villager.hasCustomName()) {
            String saved = villager.getCustomName().getString();
            if (!saved.isBlank() && saved.length() <= 80 && saved.codePoints().noneMatch(Character::isISOControl)) return saved;
        }
        return CitizenNames.forIdentity(villager.getUUID());
    }

    private static void registerIdentity(ServerLevel level, Settlement settlement, UUID entityId, String name, CitizenRole role) {
        var data = CitizenSavedData.get(level.getServer());
        if (data.byEntity(entityId).isPresent()) return;
        UUID id = UUID.nameUUIDFromBytes(("livingkingdoms:citizen:" + settlement.id() + ":" + entityId).getBytes(StandardCharsets.UTF_8));
        data.register(new Citizen(id, entityId, settlement.id(), name, new LevelValue(1), role, null,
                CitizenState.ACTIVE, Math.max(0,level.getServer().overworld().getGameTime())));
    }

    private static boolean isManaged(Villager villager, Settlement settlement) {
        return NpcIdentity.read(villager).filter(id -> id.settlementId().equals(settlement.id())
                && (id.role() == NpcRole.MAYOR || id.role() == NpcRole.RESIDENT)).isPresent();
    }

    private static boolean inTerritory(ServerLevel level, Settlement settlement, Villager villager) {
        return villager.level() == level && settlement.territory().contains(level.dimension().location().toString(),
                villager.blockPosition().getX(),villager.blockPosition().getZ());
    }

    /** Prepares a final entity without adding it: acceptance owns the housing/candidate transaction. */
    public static Optional<Villager> prepareImmigrant(ServerLevel level, Settlement settlement, Housing home) {
        validate(level, settlement);
        if (!home.settlementId().equals(settlement.id()) || !home.dimension().equals(level.dimension().location().toString())
                || home.status() != HousingStatus.ACTIVE
                || CitizenSavedData.get(level.getServer()).housing(home.id()).filter(home::equals).isEmpty()) return Optional.empty();
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) return Optional.empty();
        villager.setPersistenceRequired();
        villager.setCanPickUpLoot(true);
        for (int[] offset : SPAWN_OFFSETS) for (int dy : new int[]{0,1,-1,2,-2}) {
            BlockPos feet = home.entrance().offset(offset[0],dy,offset[1]);
            if (!settlement.territory().contains(home.dimension(),feet.getX(),feet.getZ())) continue;
            villager.moveTo(feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,0,0);
            if (!safePosition(level,villager,feet)) continue;
            villager.setVillagerData(new VillagerData(VillagerType.byBiome(level.getBiome(feet)),VillagerProfession.NONE,1));
            NpcIdentity.attach(villager,settlement.id(),NpcRole.RESIDENT);
            return Optional.of(villager);
        }
        return Optional.empty();
    }

    public static void apply(Citizen citizen, Villager villager) {
        if (!(villager.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Citizen presentation requires server thread");
        if (!citizen.entityId().equals(villager.getUUID()) || citizen.state() != CitizenState.ACTIVE)
            throw new IllegalArgumentException("Citizen identity does not match an active entity");
        if (!canApply(citizen,villager)) throw new IllegalStateException("Citizen NPC association does not match");
        var settlement = SettlementSavedData.get(level.getServer()).get(citizen.settlementId()).orElseThrow();
        villager.getPersistentData().putUUID(CITIZEN_ID_KEY,citizen.id());
        villager.setPersistenceRequired();
        if (citizen.role() == CitizenRole.MAYOR) MayorPresentation.apply(villager);
        else { villager.setCustomName(Component.literal(citizen.name())); villager.setCustomNameVisible(true); }
        BlockPos center = new BlockPos(settlement.territory().x(),settlement.territory().y(),settlement.territory().z());
        int radius = settlement.territory().radius();
        if (citizen.homeId() != null) {
            Housing home = CitizenSavedData.get(level.getServer()).houses(settlement.id()).stream()
                    .filter(h -> h.id().equals(citizen.homeId()) && h.status() == HousingStatus.ACTIVE).findFirst().orElse(null);
            if (home != null) {
                int edge = radius - (int)Math.ceil(Math.hypot(home.entrance().getX()-center.getX(),home.entrance().getZ()-center.getZ()));
                center = home.entrance();
                radius = Math.max(2,Math.min(24,edge));
            }
        }
        villager.restrictTo(center,Math.max(2,radius));
    }

    /** Loaded entities with reserved/conflicting identities are left untouched while saved history remains intact. */
    public static boolean canApply(Citizen citizen, Villager villager) {
        if (!(villager.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !villager.isAlive() || citizen.state()!=CitizenState.ACTIVE || !citizen.entityId().equals(villager.getUUID())) return false;
        var settlement = SettlementSavedData.get(level.getServer()).get(citizen.settlementId());
        if (settlement.isEmpty() || !settlement.get().faction().isAllied()
                || !settlement.get().territory().dimension().equals(level.dimension().location().toString())
                || CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID()).filter(citizen::equals).isEmpty()) return false;
        var persistent = villager.getPersistentData();
        if (persistent.contains(CITIZEN_ID_KEY) && (!persistent.hasUUID(CITIZEN_ID_KEY)
                || !persistent.getUUID(CITIZEN_ID_KEY).equals(citizen.id()))) return false;
        var identity = NpcIdentity.read(villager);
        NpcRole expected = citizen.role()==CitizenRole.MAYOR ? NpcRole.MAYOR : NpcRole.RESIDENT;
        if (identity.filter(id -> id.settlementId().equals(citizen.settlementId()) && id.role()==expected).isPresent()) return true;
        return settlement.get().provenance().origin()==SettlementOrigin.CONVERTED && NpcIdentity.isUnassigned(villager)
                && citizen.role()!=CitizenRole.MAYOR;
    }

    /** Native Brain movement receives a bounded return target only when a loaded citizen drifts outside its home. */
    public static boolean guideHome(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive() || villager.isNoAi()
                || villager.isPassenger() || villager.isLeashed() || villager.isSleeping() || villager.isTrading()
                || !villager.hasRestriction()) return false;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Citizen movement requires server thread");
        BlockPos center = villager.getRestrictCenter();
        double dx = villager.getX()-center.getX()-0.5, dz = villager.getZ()-center.getZ()-0.5;
        if (dx*dx+dz*dz <= (double)villager.getRestrictRadius()*villager.getRestrictRadius()) return false;
        for (int[] offset : SPAWN_OFFSETS) {
            BlockPos feet = center.offset(offset[0],0,offset[1]);
            // Local loaded-block reads only. Navigation, not teleportation, handles the return.
            if (!level.getChunkSource().hasChunk(feet.getX()>>4,feet.getZ()>>4)
                    || !level.getWorldBorder().isWithinBounds(feet)) continue;
            var ground = level.getBlockState(feet.below());
            if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level,feet.below(),Direction.UP)
                    || !level.getBlockState(feet).getCollisionShape(level,feet).isEmpty()
                    || !level.getBlockState(feet.above()).getCollisionShape(level,feet.above()).isEmpty()
                    || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) continue;
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(feet,0.65F,1));
            return true;
        }
        return false;
    }

    private static boolean safePosition(ServerLevel level, Villager villager, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY()+2 >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(feet)) return false;
        AABB bounds = villager.getBoundingBox();
        for (int x=((int)Math.floor(bounds.minX))>>4;x<=((int)Math.floor(bounds.maxX))>>4;x++)
            for (int z=((int)Math.floor(bounds.minZ))>>4;z<=((int)Math.floor(bounds.maxZ))>>4;z++)
                if (!level.getChunkSource().hasChunk(x,z)) return false;
        var ground = level.getBlockState(feet.below());
        if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level,feet.below(),Direction.UP)) return false;
        for (int y=0;y<=2;y++) if (!level.getFluidState(feet.above(y)).isEmpty()) return false;
        return level.noCollision(villager) && level.getEntities(villager,bounds,e -> e.isAlive() && !e.isSpectator()).isEmpty();
    }

    private static void validate(ServerLevel level, Settlement settlement) {
        var registered = SettlementSavedData.get(level.getServer()).get(settlement.id());
        if (registered.isEmpty() || !registered.get().faction().isAllied()
                || !registered.get().territory().equals(settlement.territory())
                || !settlement.territory().dimension().equals(level.dimension().location().toString()))
            throw new IllegalArgumentException("Citizen operation requires a registered allied settlement in this dimension");
    }
}
