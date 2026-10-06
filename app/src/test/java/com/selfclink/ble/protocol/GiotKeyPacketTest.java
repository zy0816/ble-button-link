package com.selfclink.ble.protocol;

import com.selfclink.ble.util.HexUtil;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiotKeyPacketTest {
    private static final String MAC = "F95910B9A65E";
    private static final String[] FRAMES = {
        "010110B9A65E9F017F99EF0304E882E7",
        "010110B9A65EA00135EE500C00F9FBE3",
        "010110B9A65EA1023C4E5F79B585A2B0"
    };

    @Test public void recordedPressesIdentifyOneOneTwo() {
        int[] expected = {1, 1, 2};
        for (int i = 0; i < FRAMES.length; i++) {
            GiotKeyPacket p = GiotKeyPacket.parse(HexUtil.fromHex(FRAMES[i]), MAC);
            assertNotNull(p);
            assertEquals(expected[i], p.key);
            assertEquals(0x9F + i, p.sequence);
        }
    }

    @Test public void signatureIgnoresChangingCounterAndOpaqueTail() {
        assertArrayEquals(GiotKeyPacket.parse(HexUtil.fromHex(FRAMES[0]), MAC).signature(),
                GiotKeyPacket.parse(HexUtil.fromHex(FRAMES[1]), MAC).signature());
        assertFalse(java.util.Arrays.equals(
                GiotKeyPacket.parse(HexUtil.fromHex(FRAMES[0]), MAC).signature(),
                GiotKeyPacket.parse(HexUtil.fromHex(FRAMES[2]), MAC).signature()));
    }

    @Test public void rejectsWrongDeviceTruncationAndUnknownFrames() {
        assertNull(GiotKeyPacket.parse(HexUtil.fromHex(FRAMES[0]), "F95910B9A65F"));
        assertNull(GiotKeyPacket.parse(new byte[15], MAC));
        byte[] unknown = HexUtil.fromHex(FRAMES[0]);
        unknown[0] = 0x10;
        assertNull(GiotKeyPacket.parse(unknown, MAC));
        unknown[0] = 1;
        unknown[7] = 0;
        assertNull(GiotKeyPacket.parse(unknown, MAC));
    }

    @Test public void learnedSignatureMatchesBothKeyOnePressesOnly() {
        com.selfclink.ble.automation.LearnedEvent event =
                new com.selfclink.ble.automation.LearnedEvent("test", "K1", 0,
                        new byte[]{(byte) 0xFF}, new byte[]{1});
        event.source = GiotKeyPacket.SOURCE;
        for (int i = 0; i < FRAMES.length; i++) {
            GiotKeyPacket packet = GiotKeyPacket.parse(HexUtil.fromHex(FRAMES[i]), MAC);
            assertEquals(i < 2, event.matchesRaw(GiotKeyPacket.SOURCE, packet.signature()));
        }
        assertFalse(event.matchesRaw("ad:7", new byte[]{1}));
    }
}
