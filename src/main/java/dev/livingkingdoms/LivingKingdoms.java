package dev.livingkingdoms;

import dev.livingkingdoms.command.SettlementCommands;
import dev.livingkingdoms.command.EncounterCommands;
import dev.livingkingdoms.encounter.EncounterEvents;
import dev.livingkingdoms.encounter.EncounterMaintenance;
import dev.livingkingdoms.encounter.NaturalEncounterSpawner;
import dev.livingkingdoms.faction.FactionCombat;
import dev.livingkingdoms.progression.ProgressionEvents;
import dev.livingkingdoms.command.ProgressionCommands;
import dev.livingkingdoms.command.QuestCommands;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.quest.QuestInteractions;
import dev.livingkingdoms.npc.NpcInteractions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/** Common bootstrap: no client classes may be referenced here. */
@Mod(LivingKingdoms.MOD_ID)
public final class LivingKingdoms {
    public static final String MOD_ID = "livingkingdoms";

    public LivingKingdoms(IEventBus modBus, ModContainer container) {
        KingdomBlocks.register(modBus);
        dev.livingkingdoms.item.KingdomItems.register(modBus);
        modBus.addListener(dev.livingkingdoms.ui.UiPayloads::register);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.npc.MayorPresentation::onJoin);
        container.registerConfig(ModConfig.Type.SERVER, KingdomConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(SettlementCommands::register);
        NeoForge.EVENT_BUS.addListener(EncounterCommands::register);
        NeoForge.EVENT_BUS.addListener(ProgressionCommands::register);
        NeoForge.EVENT_BUS.addListener(QuestCommands::register);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.command.ConstructionCommands::register);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.command.ImmigrationCommands::register);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.citizen.CitizenEvents::onJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, dev.livingkingdoms.citizen.CitizenEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.citizen.CitizenEvents::onConversion);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.citizen.CitizenEvents::onTick);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.citizen.ImmigrationService::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.FarmerWork::onJoin);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.FarmerWork::onTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, dev.livingkingdoms.profession.FarmerWork::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.FarmerWork::onConversion);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.command.FarmCommands::register);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.GuardWork::onJoin);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.GuardWork::onLeave);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.GuardWork::onTick);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.SecurityService::onEncounterTick);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.GuardWork::onDamage);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,dev.livingkingdoms.profession.GuardWork::beforeDamage);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,dev.livingkingdoms.profession.GuardWork::onDeath);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.command.GuardCommands::register);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.command.DefenseCommands::register);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.defense.DefenseService::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.defense.DefenseService::onLogin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EncounterEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(EncounterEvents::onConversion);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.profession.GuardWork::onConversion);
        NeoForge.EVENT_BUS.addListener(EncounterEvents::onDamage);
        NeoForge.EVENT_BUS.addListener(FactionCombat::onJoin);
        NeoForge.EVENT_BUS.addListener(ProgressionEvents::onJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, FactionCombat::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(NaturalEncounterSpawner::onLevelTick);
        NeoForge.EVENT_BUS.addListener(EncounterMaintenance::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.construction.ConstructionEvents::onTick);
        NeoForge.EVENT_BUS.addListener(dev.livingkingdoms.construction.ConstructionEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(QuestInteractions::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(NpcInteractions::onInteract);
        NeoForge.EVENT_BUS.addListener(NpcInteractions::onInteractSpecific);
    }
}
