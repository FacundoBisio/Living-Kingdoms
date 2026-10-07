package dev.livingkingdoms.quest;

import dev.livingkingdoms.block.KingdomBlocks;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Keep crouch-click usable even while holding the requested resources. */
public final class QuestInteractions {
    private QuestInteractions() {}

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().getBlockState(event.getPos()).is(KingdomBlocks.QUEST_BOARD)) {
            event.setUseBlock(TriState.TRUE);
            event.setUseItem(TriState.FALSE);
        }
    }
}
