package dev.livingkingdoms.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.livingkingdoms.config.ClientHudConfig;
import dev.livingkingdoms.ui.ShortcutAction;
import dev.livingkingdoms.ui.UiPayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.EnumMap;

final class KingdomKeys {
    private static final EnumMap<ShortcutAction,KeyMapping> KEYS=new EnumMap<>(ShortcutAction.class);
    private KingdomKeys() {}
    static void register(RegisterKeyMappingsEvent event) {
        registerMappings(event::register);
    }
    static void registerMappings(java.util.function.Consumer<KeyMapping> controls) {
        for(var action:ShortcutAction.values()) {
            var key=new KeyMapping(action.titleKey(),KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,action.defaultKey(),"key.categories.livingkingdoms");
            KEYS.put(action,key); controls.accept(key);
        }
    }
    static void tick(ClientTickEvent.Post event) {
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null) KingdomHud.clear();
        for(var entry:KEYS.entrySet()) while(entry.getValue().consumeClick()) {
            // Chat, inventory and other screens own their keyboard input; do not queue deferred opens.
            if(client.player==null || client.level==null || client.screen!=null) continue;
            if(entry.getKey()==ShortcutAction.HUD) {
                boolean enabled=!ClientHudConfig.HUD_ENABLED.get();
                ClientHudConfig.HUD_ENABLED.set(enabled); ClientHudConfig.SPEC.save();
                client.player.displayClientMessage(Component.translatable(enabled?"hud.livingkingdoms.enabled":"hud.livingkingdoms.disabled"),true);
            } else PacketDistributor.sendToServer(new UiPayloads.Shortcut(entry.getKey()));
        }
    }
}
