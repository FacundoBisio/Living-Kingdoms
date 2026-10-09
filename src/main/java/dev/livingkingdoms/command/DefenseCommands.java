package dev.livingkingdoms.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.domain.ThreatOutcome;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator-only fixtures. Resolve cancels a living threat; it never fabricates kills or rewards. */
public final class DefenseCommands {
    private DefenseCommands() {}
    public static void register(RegisterCommandsEvent event) {
        var trigger = Commands.literal("trigger").executes(c -> trigger(c.getSource(), PartyType.PILLAGER_PATROL, false));
        trigger.then(Commands.literal("reward_test").executes(c -> trigger(c.getSource(), PartyType.PILLAGER_PATROL, true)));
        for (PartyType type : PartyType.values()) {
            String name = type == PartyType.PILLAGER_PATROL ? "pillager" : "undead";
            trigger.then(Commands.literal(name).executes(c -> trigger(c.getSource(), type, false))
                    .then(Commands.literal("reward_test").executes(c -> trigger(c.getSource(), type, true))));
        }
        event.getDispatcher().register(Commands.literal("kingdom").then(Commands.literal("defense")
                .requires(s -> s.hasPermission(2)).then(trigger)
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("resolve").executes(c -> resolve(c.getSource())))));
    }
    private static Settlement local(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException(); var pos = player.blockPosition();
        return SettlementSavedData.get(source.getServer()).at(player.serverLevel().dimension().location().toString(), pos.getX(), pos.getZ())
                .filter(s -> s.faction().isAllied()).orElse(null);
    }
    private static int trigger(CommandSourceStack source, PartyType type, boolean rewardTest) throws CommandSyntaxException {
        var settlement = local(source);
        if (settlement == null || DefenseService.current(source.getServer(), settlement.id()).isPresent()) return rejected(source);
        var level = source.getPlayerOrException().serverLevel();
        // Attach an existing loaded member first. Origins alone cannot establish a local threat.
        for (var party : EncounterSavedData.get(source.getServer()).activeNearby(level.dimension().location().toString(),
                settlement.territory().x(), settlement.territory().z(), dev.livingkingdoms.config.DefenseConfig.MAX_DETECTION_RANGE.get())) {
            if (party.type() != type || DefenseService.eventForParty(source.getServer(), party.id()).isPresent()) continue;
            for (var id : party.remainingMembers()) {
                var member = level.getEntity(id);
                if (member instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()
                        && DefenseService.detectMember(level, living).filter(e -> e.settlementId().equals(settlement.id())).isPresent())
                    return created(source, settlement);
            }
        }
        var center = new net.minecraft.core.BlockPos(settlement.territory().x(), settlement.territory().y(), settlement.territory().z());
        var spawned = EncounterSpawner.spawnNear(level, center, type, rewardTest);
        if (!spawned.successful()) return rejected(source);
        for (var id : spawned.party().remainingMembers()) {
            var member = level.getEntity(id);
            if (member instanceof net.minecraft.world.entity.LivingEntity living
                    && DefenseService.detectMember(level, living).filter(e -> e.settlementId().equals(settlement.id())).isPresent())
                return created(source, settlement);
        }
        // A tiny territory or unusual terrain may place the test party beyond the defense radius.
        EncounterSavedData.get(source.getServer()).remove(spawned.party().id());
        for (var id : spawned.party().remainingMembers()) { var mob = level.getEntity(id); if (mob != null) mob.discard(); }
        return rejected(source);
    }
    private static int created(CommandSourceStack source, Settlement settlement) {
        source.sendSuccess(() -> Component.translatable("defense.livingkingdoms.debug_created", settlement.name()), false); return 1;
    }
    private static int rejected(CommandSourceStack source) {
        source.sendFailure(Component.translatable("defense.livingkingdoms.debug_rejected")); return 0;
    }
    private static int info(CommandSourceStack source) throws CommandSyntaxException {
        var settlement = local(source); if (settlement == null) return rejected(source);
        DefenseService.current(source.getServer(), settlement.id());
        var event = DefenseService.latest(source.getServer(), settlement.id());
        if (event.isEmpty()) { source.sendSuccess(() -> Component.translatable("defense.livingkingdoms.debug_none"), false); return 1; }
        var threat = event.get();
        source.sendSuccess(() -> Component.translatable("defense.livingkingdoms.debug_info", settlement.name(),
                Component.translatable("defense.livingkingdoms.state." + threat.state().name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable("faction.livingkingdoms." + threat.faction().id()), threat.remainingMembers(), threat.totalMembers()), false);
        return 1;
    }
    private static int resolve(CommandSourceStack source) throws CommandSyntaxException {
        var settlement = local(source); if (settlement == null) return rejected(source);
        var event = DefenseService.current(source.getServer(), settlement.id());
        if (event.isEmpty()) return rejected(source);
        if (!DefenseService.fail(source.getServer(), event.get(), ThreatOutcome.DEBUG_CANCELED)) return rejected(source);
        source.sendSuccess(() -> Component.translatable("defense.livingkingdoms.debug_resolved"), false); return 1;
    }
}
