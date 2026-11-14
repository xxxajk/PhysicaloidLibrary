package com.physicaloid.lib.programmer.avr;

/**
 * Native urprotocol for ATmega328P, based on the published protocol specification:
 * https://github.com/stefanrueger/urboot/blob/main/urprotocol.md
 * Hardware boot section programming only; this is not a full AVRDUDE urclock port.
 */
public class Urclock extends Stk500 {
    private int inSync, ok, features;
    private boolean identified;

    private int receive(byte[] data, int timeoutMs) {
        int done = 0;
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        byte[] chunk = new byte[data.length];
        while (done < data.length) {
            if (Thread.currentThread().isInterrupted()) return -1;
            int count = mComm.read(chunk, data.length - done);
            if (count < 0 || count > data.length - done) return -1;
            if (count > 0) {
                System.arraycopy(chunk, 0, data, done, count);
                done += count;
            }
            if (System.nanoTime() >= deadline) return -1;
        }
        return done;
    }

    @Override public int open() {
        identified = false;
        if (!mComm.setDtrRts(false, false)) return -1;
        try { Thread.sleep(50); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
        if (!mComm.setDtrRts(true, true)) return -1;
        mComm.clearBuffer();
        byte[] previous = null;
        byte[] sync = { 0x30, 0x20 };
        for (int attempt = 0; attempt < 12; attempt++) {
            if (Thread.currentThread().isInterrupted()) return -1;
            if (mComm.write(sync, 2) != 2) return -1;
            byte[] reply = new byte[2];
            if (receive(reply, 80) != 2 || reply[0] == reply[1]) { previous = null; continue; }
            if (previous != null && java.util.Arrays.equals(previous, reply)) {
                inSync = reply[0] & 255; ok = reply[1] & 255;
                // STK compatibility mode must use an ordinary STK500 board profile.
                if (inSync == 0x14 && ok == 0x10) return -1;
                int info = inSync == 255 && ok == 254 ? 0x14 * 255 + 0x10
                    : inSync * 255 + ok - (ok > inSync ? 1 : 0);
                features = info / 2040;
                // Published MCU ID 119 is ATmega328P. Require native flash read support.
                identified = info % 2040 == 119 && (features & 4) != 0
                    && mAVRMem.size == 32768 && mAVRMem.page_size == 128;
                return identified ? 0 : -1;
            }
            previous = reply;
            mComm.clearBuffer();
        }
        return -1;
    }

    @Override public void enable() { }
    @Override public int initialize() { return identified ? 0 : -1; }
    @Override public int check_sig_bytes() { return identified ? 0 : -1; }

    private byte[] readBlock(int address, int count) {
        if (!identified || address < 0 || count < 1 || count > 256 || address + count > 32768) return null;
        byte[] command = { 0x03, (byte)address, (byte)(address >> 8), (byte)count, 0x20 };
        if (mComm.write(command, command.length) != command.length) return null;
        byte[] reply = new byte[count + 2];
        if (receive(reply, 1000) != reply.length || (reply[0] & 255) != inSync
                || (reply[count + 1] & 255) != ok) return null;
        byte[] data = new byte[count];
        System.arraycopy(reply, 1, data, 0, count);
        return data;
    }

    @Override public int paged_read() {
        if (!identified || mAVRMem.buf.length < 1 || mAVRMem.buf.length > 32768) return -1;
        for (int address = 0; address < mAVRMem.buf.length; address += 128) {
            if (Thread.currentThread().isInterrupted()) return 0;
            int count = Math.min(128, mAVRMem.buf.length - address);
            byte[] data = readBlock(address, count);
            if (data == null) return -1;
            System.arraycopy(data, 0, mAVRMem.buf, address, count);
            report_progress((address + count) * 100 / mAVRMem.buf.length);
        }
        return mAVRMem.buf.length;
    }

    @Override public int paged_write() {
        // NOR-style programming needs erase management, which is not implemented here.
        if (!identified || (features & 8) != 0) return -1;
        byte[] table = readBlock(32762, 6);
        if (table == null) return -1;
        int version = table[5] & 255, capabilities = table[4] & 255;
        int bootPages = table[0] & 255;
        // Accept known table layouts only, and reject every vector-bootloader variant.
        if (version < 61 || version > 64 || (capabilities & 12) != 0
                || table[1] != 0 || bootPages == 0 || bootPages > 127) return -1;
        int length = mAVRMem.buf.length;
        if (length == 0 || length > 32768 - bootPages * 128) return -1;
        for (int address = 0; address < length; address += 128) {
            if (Thread.currentThread().isInterrupted()) { report_cancel(); return 0; }
            byte[] command = new byte[133];
            java.util.Arrays.fill(command, (byte)0xff);
            command[0] = 0x02; command[1] = (byte)address;
            command[2] = (byte)(address >> 8); command[3] = (byte)128;
            System.arraycopy(mAVRMem.buf, address, command, 4, Math.min(128, length - address));
            command[132] = 0x20;
            if (mComm.write(command, command.length) != command.length) return -1;
            byte[] ack = new byte[2];
            if (receive(ack, 1000) != 2 || (ack[0] & 255) != inSync || (ack[1] & 255) != ok) return -1;
            byte[] verify = readBlock(address, 128);
            if (verify == null) return -1;
            for (int i = 0; i < 128; i++) if (verify[i] != command[4 + i]) return -1;
            report_progress(Math.min(address + 128, length) * 100 / length);
        }
        return length;
    }

    @Override public void disable() {
        if (!identified) return;
        byte[] leave = { 0x51, 0x20 };
        if (mComm.write(leave, 2) == 2) receive(new byte[2], 80);
        identified = false;
    }
}
