package com.physicaloid.lib.programmer.avr;

import com.physicaloid.lib.Boards;
import com.physicaloid.lib.Physicaloid;
import com.physicaloid.lib.framework.Uploader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

public class Avr109Test {
    private static final Boards BOARD = Boards.ARDUINO_LEONARDO;

    private static final class TestDevice extends Physicaloid {
        TestDevice(Caterina serial) {
            super(null);
            mSerial = serial;
        }
    }

    private static class Caterina extends FakeSerial {
        final byte[] flash = new byte[32768];
        int address;
        int pagesWritten;
        boolean wrongChip;
        boolean corruptRead;
        boolean truncateRead;
        boolean partialWrite;

        @Override
        void respond(byte[] command) {
            switch (command[0]) {
                case 'S':
                    reply("CATERIN".getBytes(StandardCharsets.US_ASCII));
                    break;
                case 'b':
                    reply(new byte[]{'Y', 0, (byte) 128});
                    break;
                case 's':
                    reply(new byte[]{wrongChip ? (byte) 0x0f : (byte) 0x87, (byte) 0x95, 0x1e});
                    break;
                case 'A':
                    assertEquals(3, command.length);
                    address = (((command[1] & 0xff) << 8) | (command[2] & 0xff)) * 2;
                    reply(new byte[]{'\r'});
                    break;
                case 'B':
                    assertEquals(132, command.length);
                    assertEquals(0, address % 128);
                    assertTrue(address < 28672);
                    assertEquals('F', command[3]);
                    assertEquals(0, command[1]);
                    assertEquals(128, command[2] & 0xff);
                    pagesWritten++;
                    System.arraycopy(command, 4, flash, address, 128);
                    reply(new byte[]{'\r'});
                    break;
                case 'g':
                    assertEquals(4, command.length);
                    assertEquals('F', command[3]);
                    int length = ((command[1] & 0xff) << 8) | (command[2] & 0xff);
                    byte[] reply = Arrays.copyOfRange(flash, address, address + length);
                    if (corruptRead) reply[0] ^= 1;
                    if (truncateRead) reply = Arrays.copyOf(reply, length - 1);
                    reply(reply);
                    break;
                case 'P':
                case 'L':
                case 'E':
                    reply(new byte[]{'\r'});
                    break;
                default:
                    fail("Unexpected command " + (char) command[0]);
            }
        }

        @Override
        public int write(byte[] command, int length) {
            if (partialWrite && command[0] == 'B') {
                writes.add(Arrays.copyOf(command, 8));
                return 8;
            }
            return super.write(command, length);
        }
    }

    @Test
    public void fragmentedReadbackUsesPublicApiAndLeavesBootloader() throws Exception {
        Caterina serial = new Caterina();
        for (int i = 0; i < serial.flash.length; i++) {
            serial.flash[i] = (byte) ((i >>> 7) ^ i);
        }
        Physicaloid device = new TestDevice(serial);
        assertArrayEquals(serial.flash, device.readFlash(BOARD));
        assertEquals(0, serial.pagesWritten);
        assertEquals('E', serial.writes.get(serial.writes.size() - 1)[0]);
    }

    @Test
    public void uploadUsesRecordAddressesAndPadsPartialPage() {
        Caterina serial = new Caterina();
        // One byte at 0x0000 and another at 0x0081. Byte count alone would miss the second page.
        assertTrue(new Uploader().upload(hex(":0100000012ED\n:01008100344A\n:00000001FF\n"),
                BOARD, serial, null));
        assertEquals(2, serial.pagesWritten);
        assertEquals(0x12, serial.flash[0]);
        assertEquals((byte) 0xff, serial.flash[128]);
        assertEquals(0x34, serial.flash[129]);
        assertEquals((byte) 0xff, serial.flash[255]);
        assertEquals(0, serial.flash[256]);
    }

    @Test
    public void acceptsFinalApplicationByteAndRejectsBootSectionOrExtendedAddress() {
        Caterina serial = new Caterina();
        assertTrue(new Uploader().upload(hex(":016FFF007F12\n:00000001FF\n"), BOARD, serial, null));
        assertEquals(28672 / 128, serial.pagesWritten);
        assertEquals(0x7f, serial.flash[28671]);
        assertEquals(0, serial.flash[28672]);

        String[] invalid = {
            ":017000007F10\n:00000001FF\n",
            ":020000040001F9\n:0100000012ED\n:00000001FF\n"
        };
        for (String image : invalid) {
            Caterina rejected = new Caterina();
            assertFalse(new Uploader().upload(hex(image), BOARD, rejected, null));
            assertTrue(rejected.writes.isEmpty());
        }
    }

    @Test
    public void rejectsWrongChipAndVerificationMismatch() {
        Caterina wrongChip = new Caterina();
        wrongChip.wrongChip = true;
        assertFalse(uploadOneByte(wrongChip));
        assertEquals(0, wrongChip.pagesWritten);
        Caterina mismatch = new Caterina();
        mismatch.corruptRead = true;
        assertFalse(uploadOneByte(mismatch));
        assertEquals(1, mismatch.pagesWritten);
    }

    @Test
    public void truncatedReadProducesNoHexAndPartialCommandGetsNoExitBytes() throws Exception {
        Caterina serial = new Caterina();
        serial.truncateRead = true;
        Physicaloid device = new TestDevice(serial);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            device.download(BOARD, output, null);
            fail("Expected an incomplete-read error");
        } catch (IOException expected) {
            assertEquals(0, output.size());
        }

        Caterina partial = new Caterina();
        partial.partialWrite = true;
        assertFalse(uploadOneByte(partial));
        assertEquals('B', partial.writes.get(partial.writes.size() - 1)[0]);
    }

    private static boolean uploadOneByte(Caterina serial) {
        return new Uploader().upload(hex(":0100000012ED\n:00000001FF\n"), BOARD, serial, null);
    }

    private static ByteArrayInputStream hex(String records) {
        return new ByteArrayInputStream(records.getBytes(StandardCharsets.US_ASCII));
    }
}
