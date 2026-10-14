package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import dev.livingkingdoms.chronicle.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChronicleGameTests {
    @GameTest(template="empty", timeoutTicks=200)
    public static void registeredCitizenRealDeathNotifiesOnlyJoinedMemberAndReloadDoesNotReplay(GameTestHelper h) {
        var level = h.getLevel(); var server = level.getServer(); var owner = new AlertPlayer(level); var visitor = new AlertPlayer(level);
        var center = h.absolutePos(new BlockPos(1301728, 1, 1301728));
        var settlement = Settlement.established(UUID.randomUUID(), new Territory(level.dimension().location().toString(), center.getX(), center.getY(), center.getZ(), 24), 1, SettlementOrigin.FOUNDED, owner.getUUID());
        SettlementSavedData.get(server).add(settlement);
        KingdomOnboardingSavedData.get(server).enrollLegacy(owner.getUUID(), settlement.id(), "Chronicle Realm");
        var data = ChronicleSavedData.get(server);
        data.observeTransitions(event -> { ChronicleService.deliver(owner, event); ChronicleService.deliver(visitor, event); });
        try {
            var citizenEntity = EntityType.VILLAGER.create(level); citizenEntity.moveTo(h.absolutePos(new BlockPos(1, 1, 1)).getCenter()); citizenEntity.setNoAi(true); level.addFreshEntity(citizenEntity);
            var citizen = new Citizen(UUID.randomUUID(), citizenEntity.getUUID(), settlement.id(), "Ada", new LevelValue(1), CitizenRole.UNASSIGNED, null, CitizenState.ACTIVE, 0);
            CitizenSavedData.get(server).register(citizen);
            h.assertTrue(!ChronicleService.citizenDied(server, citizen, false) && owner.notices.isEmpty(), "Active or unloaded citizens never produce a death alert");
            citizenEntity.hurt(level.damageSources().genericKill(), 1000);
            h.assertTrue(CitizenSavedData.get(server).citizen(citizen.id()).orElseThrow().state() == CitizenState.DEAD, "Real death commits the existing identity receipt");
            h.assertTrue(owner.notices.size() == 1 && visitor.notices.isEmpty(), "Actual death event sends one personal native payload to joined member");
            var notice = owner.notices.getFirst();
            h.assertTrue(notice.event().kind() == ChronicleEvent.Kind.CITIZEN_DIED && notice.event().subject().equals("Ada"), "Death notification carries the registered name and truthful kind");
            h.assertTrue(!ChronicleService.citizenDied(server, citizen, false), "Repeated death hook is deduplicated");
            var loaded = ChronicleSavedData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(!loaded.record(notice.event()) && loaded.history(settlement.id()).size() == 1 && owner.notices.size() == 1, "Reload retains receipt without notification replay");
            ChronicleService.history(server, settlement.id());
            h.assertTrue(owner.notices.size() == 1, "Viewing history emits no toast");
        } finally { data.observeTransitions(event -> ChronicleService.send(server, event)); }
        h.succeed();
    }
    @GameTest(template="empty", timeoutTicks=200)
    public static void newAdvancementsUseVanillaPersistentOneTimeReceipts(GameTestHelper h) {
        var player = new AlertPlayer(h.getLevel());
        for (String key : List.of("converted_village", "first_warehouse", "all_professions", "first_upgrade", "first_trade", "development3")) {
            h.assertTrue(ChronicleAdvancements.grant(player, key), "Native advancement criteria load and grant: " + key);
            h.assertTrue(!ChronicleAdvancements.grant(player, key), "Advancement completion receipt grants only once: " + key);
        }
        h.succeed();
    }
    private static final class AlertPlayer extends FakePlayer {
        final List<ChroniclePayloads.Notice> notices = new ArrayList<>();
        private net.minecraft.server.PlayerAdvancements advancements;
        AlertPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "ChronicleTest"));
            Connection wire = new Connection(PacketFlow.SERVERBOUND) { @Override public void setListenerForServerboundHandshake(PacketListener listener) {} };
            connection = new ServerGamePacketListenerImpl(level.getServer(), wire, this, CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof ChroniclePayloads.Notice notice) notices.add(notice);
                }
            };
        }
        @Override public net.minecraft.server.PlayerAdvancements getAdvancements() {
            if (advancements == null) {
                var path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("chronicle-test-advancements").resolve(getUUID() + ".json");
                advancements = new net.minecraft.server.PlayerAdvancements(server.getFixerUpper(), server.getPlayerList(), server.getAdvancements(), path, this);
            }
            return advancements;
        }
    }
}
