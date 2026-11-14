package com.physicaloid.lib.programmer.avr;

import java.io.IOException;
import java.io.OutputStream;

/** Intel HEX export with extended linear addresses and an EOF record. */
public final class IntelHexWriter {
    private IntelHexWriter() { }

    public static void write(byte[] flash, OutputStream output) throws IOException {
        if (flash == null || output == null) throw new IllegalArgumentException("flash/output is null");
        for (int address = 0; address < flash.length; address += 16) {
            if (address > 0 && (address & 0xffff) == 0) {
                byte[] upper = { (byte)(address >>> 24), (byte)(address >>> 16) };
                record(output, 4, 0, upper, 0, 2);
            }
            record(output, 0, address & 0xffff, flash, address, Math.min(16, flash.length - address));
        }
        record(output, 1, 0, flash, 0, 0);
    }

    private static void record(OutputStream output, int type, int address,
            byte[] data, int offset, int count) throws IOException {
        StringBuilder line = new StringBuilder(45);
        line.append(':');
        hex(line, count); hex(line, address >> 8); hex(line, address); hex(line, type);
        int sum = count + (address >> 8) + (address & 255) + type;
        for (int i = 0; i < count; i++) {
            int value = data[offset + i] & 255;
            hex(line, value); sum += value;
        }
        hex(line, -sum);
        line.append('\n');
        output.write(line.toString().getBytes("US-ASCII"));
    }

    private static void hex(StringBuilder text, int value) {
        final String digits = "0123456789ABCDEF";
        text.append(digits.charAt((value >> 4) & 15));
        text.append(digits.charAt(value & 15));
    }
}
