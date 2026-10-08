package dev.livingkingdoms.ui;

import dev.livingkingdoms.npc.MayorPresentation;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.faction.Faction;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VillageIdentityTest {
    @Test void mayorNamesAreStableVariedAndContainNoTechnicalIds() {
        var names = new HashSet<String>();
        for (int i=0;i<120;i++) {
            UUID id = new UUID(42,i);
            String name = MayorPresentation.name(id);
            assertEquals(name, MayorPresentation.name(id));
            assertTrue(name.matches("[A-Za-z]+"));
            assertFalse(name.contains(id.toString())); names.add(name);
        }
        assertEquals(MayorPresentation.NAMES.size(), names.size());
    }
    @Test void oldDefaultSettlementNamesAreAliasedWithoutChangingSavedData() {
        UUID id=UUID.randomUUID();
        var old = new Settlement(id,"Haven "+id.toString().substring(0,8),Faction.ALLIED,1,0,new Territory("minecraft:overworld",0,64,0,48));
        assertEquals(VillageNames.generated(id),VillageNames.display(old));
        assertTrue(old.name().startsWith("Haven "));
        var custom=new Settlement(id,"My Haven",old.faction(),1,0,old.territory());
        assertEquals("My Haven",VillageNames.display(custom));
    }
    @Test void sessionRequestsRoundTripWithoutRewardsOrClientOwnedState() {
        for(var action:UiPayloads.Action.values()) {
            var request=new UiPayloads.Request(UUID.randomUUID(),UUID.randomUUID(),action);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try {UiPayloads.Request.CODEC.encode(buffer,request);assertEquals(request,UiPayloads.Request.CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());}
            finally {buffer.release();}
        }
    }
    @Test void snapshotPreservesUnicodeLocalizationKeysAndNumericProgress() {
        CompoundTag tag=new CompoundTag();tag.putUUID("session",UUID.randomUUID());tag.putString("screen","board");
        tag.putString("settlement","Peñaflor");tag.putString("objective","ui.livingkingdoms.deliver");tag.putInt("count",13);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {UiPayloads.Snapshot.CODEC.encode(buffer,new UiPayloads.Snapshot(tag));assertEquals(tag,UiPayloads.Snapshot.CODEC.decode(buffer).data());}
        finally {buffer.release();}
    }
    @Test void malformedClientActionIsRejectedDuringDecode() {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {buffer.writeUUID(UUID.randomUUID());buffer.writeUUID(UUID.randomUUID());buffer.writeVarInt(999);
            assertThrows(RuntimeException.class,()->UiPayloads.Request.CODEC.decode(buffer));}
        finally {buffer.release();}
    }
}
