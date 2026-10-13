package dev.livingkingdoms.ui;

import dev.livingkingdoms.quest.domain.QuestNotice;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UiPayloadCodecTest {
    private static RegistryFriendlyByteBuf buffer() {return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.NEOFORGE);}
    private static QuestNotice notice() {return new QuestNotice(UUID.randomUUID(),QuestNotice.Kind.OBJECTIVE_COMPLETED,QuestTemplate.MAIN_DEVELOPMENT,UUID.randomUUID(),2,3);}
    @Test void validLegacySettlementNamesRoundTripWithoutTruncatingOrDisconnectingPlayer() {
        for(String name:new String[]{"Peña del Río","Á".repeat(128),""}) {
            var buf=buffer();try {
                var payload=new UiPayloads.QuestNotification(notice(),name);UiPayloads.QuestNotification.CODEC.encode(buf,payload);
                assertTrue(buf.readableBytes()<512);assertEquals(payload,UiPayloads.QuestNotification.CODEC.decode(buf));assertEquals(0,buf.readableBytes());
            } finally {buf.release();}
        }
    }
    @Test void constructionFundingRequestPreservesExactSessionTargetAndActionOnWire() {
        var buf=buffer();try {
            var request=new UiPayloads.Request(UUID.randomUUID(),UUID.randomUUID(),UiPayloads.Action.DEPOSIT_STOCK);
            UiPayloads.Request.CODEC.encode(buf,request);assertEquals(request,UiPayloads.Request.CODEC.decode(buf));assertEquals(0,buf.readableBytes());
        } finally {buf.release();}
    }
    @Test void oversizeNamesAndMalformedRewardsAreRejectedByPayloadBoundary() {
        assertThrows(IllegalArgumentException.class,()->new UiPayloads.QuestNotification(notice(),"a".repeat(129)));
        var buf=buffer();try {
            var n=notice();buf.writeUUID(n.quest());buf.writeEnum(n.kind());buf.writeEnum(n.template());buf.writeUUID(n.settlement());buf.writeVarInt(-1);buf.writeVarInt(3);buf.writeUtf("Peña",128);
            assertThrows(IllegalArgumentException.class,()->UiPayloads.QuestNotification.CODEC.decode(buf));
        } finally {buf.release();}
    }
}
