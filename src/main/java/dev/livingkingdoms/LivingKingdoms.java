package dev.livingkingdoms;

import dev.livingkingdoms.command.SettlementCommands;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.KingdomConfig;
import net.neoforged.bus.api.IEventBus;
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
        container.registerConfig(ModConfig.Type.SERVER, KingdomConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(SettlementCommands::register);
    }
}
