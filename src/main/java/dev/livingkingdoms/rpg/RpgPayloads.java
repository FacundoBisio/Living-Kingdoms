package dev.livingkingdoms.rpg;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.rpg.domain.ChallengeTier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

/** RPG layer packets. Clients only ever send a tier choice; rewards and outcomes are server-decided. */
public final class RpgPayloads {
    private RpgPayloads() {}
    public static Consumer<CompoundTag> rewardReceiver = ignored -> {};
    public static Consumer<CompoundTag> contractReceiver = ignored -> {};

    public record Contracts(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Contracts> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "contracts"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Contracts> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeNbt(value.data), buf -> new Contracts(java.util.Objects.requireNonNull(buf.readNbt())));
        @Override public Type<Contracts> type() { return TYPE; }
    }

    public record ContractClaim(int slot) implements CustomPacketPayload {
        public static final Type<ContractClaim> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "contract_claim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ContractClaim> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeVarInt(value.slot), buf -> new ContractClaim(buf.readVarInt()));
        @Override public Type<ContractClaim> type() { return TYPE; }
    }

    /** Display-only celebration of rewards the server already granted. */
    public record RewardBanner(CompoundTag data) implements CustomPacketPayload {
        public static final Type<RewardBanner> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "reward_banner"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RewardBanner> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeNbt(value.data), buf -> new RewardBanner(java.util.Objects.requireNonNull(buf.readNbt())));
        @Override public Type<RewardBanner> type() { return TYPE; }
    }

    public record ChallengeStart(ChallengeTier tier) implements CustomPacketPayload {
        public static final Type<ChallengeStart> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "challenge_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeStart> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeEnum(value.tier), buf -> new ChallengeStart(buf.readEnum(ChallengeTier.class)));
        @Override public Type<ChallengeStart> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(RewardBanner.TYPE, RewardBanner.CODEC, (payload, context) -> rewardReceiver.accept(payload.data()));
        registrar.playToClient(Contracts.TYPE, Contracts.CODEC, (payload, context) -> contractReceiver.accept(payload.data()));
        registrar.playToServer(ContractClaim.TYPE, ContractClaim.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) ContractService.claim(player, payload.slot());
        });
        registrar.playToServer(ChallengeStart.TYPE, ChallengeStart.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) ChallengeService.start(player, payload.tier(), false);
        });
    }
}
