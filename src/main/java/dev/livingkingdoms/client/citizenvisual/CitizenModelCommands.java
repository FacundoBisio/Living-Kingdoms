package dev.livingkingdoms.client.citizenvisual;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Local comparison control: sends no packet, requires no operator privileges, never changes saved entities. */
final class CitizenModelCommands {
    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdommodels")
                .then(Commands.literal("native").executes(context -> select(CitizenVisualConfig.ModelStyle.NATIVE)))
                .then(Commands.literal("argon4").executes(context -> select(CitizenVisualConfig.ModelStyle.ARGON4))));
    }
    private static int select(CitizenVisualConfig.ModelStyle style) {
        CitizenVisualConfig.MODEL.set(style);
        CitizenVisualConfig.SPEC.save();
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.player != null) client.player.displayClientMessage(Component.translatable(
                "citizen.livingkingdoms.model_changed", style.name()), false);
        return 1;
    }
    private CitizenModelCommands() {}
}
