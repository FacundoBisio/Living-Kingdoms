package dev.livingkingdoms.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.livingkingdoms.ui.ShortcutAction;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class KingdomKeysTest {
    @Test void controlsRegistrationCreatesThreeNativeRemappableInGameBindings() {
        var controls=new ArrayList<KeyMapping>(); KingdomKeys.registerMappings(controls::add);
        assertEquals(3,controls.size());
        for(int i=0;i<controls.size();i++) {
            var key=controls.get(i); var action=ShortcutAction.values()[i];
            assertEquals(action.titleKey(),key.getName()); assertEquals(action.defaultKey(),key.getDefaultKey().getValue());
            assertEquals("key.categories.livingkingdoms",key.getCategory()); assertSame(KeyConflictContext.IN_GAME,key.getKeyConflictContext());
            var remapped=InputConstants.Type.KEYSYM.getOrCreate(76+i); key.setKey(remapped);
            assertEquals(remapped,key.getKey()); assertEquals(action.defaultKey(),key.getDefaultKey().getValue());
        }
        KeyMapping.resetMapping();
    }
}
