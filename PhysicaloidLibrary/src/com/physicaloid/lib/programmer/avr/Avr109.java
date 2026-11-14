package com.physicaloid.lib.programmer.avr;

import com.physicaloid.lib.framework.SerialCommunicator;
import java.util.Arrays;

/** AVR109 flash operations for the ATmega32U4 Caterina bootloader. */
final class Avr109 extends UploadProtocol {
    static final int APPLICATION_SIZE = 28 * 1024;
    private static final int REPLY_TIMEOUT_MS = 1000;
    // Caterina reports the first seven bytes of its software identifier.
    private static final byte[] CATERINA_ID = {'C', 'A', 'T', 'E', 'R', 'I', 'N'};
    private static final byte ACK = '\r';

    private SerialCommunicator serial;
    private AvrConf config;
    private AVRMem flash;
    private boolean identified;
    private boolean atCommandBoundary;
    private boolean signatureMatches;

    @Override
    public void setSerial(SerialCommunicator serial) {
        this.serial = serial;
    }

    @Override
    public void setConfig(AvrConf config, AVRMem flash) {
        this.config = config;
        this.flash = flash;
    }

    @Override
    public int open() {
        identified = false;
        signatureMatches = false;
        atCommandBoundary = true;
        if (!Arrays.equals(CATERINA_ID, exchange(new byte[]{'S'}, CATERINA_ID.length))) {
            return -1;
        }
        identified = true;
        return 0;
    }

    @Override
    public void enable() { }

    @Override
    public int initialize() {
        if (!identified) return -1;
        byte[] blockMode = exchange(new byte[]{'b'}, 3);
        if (blockMode == null || blockMode[0] != 'Y') return -1;
        int blockSize = ((blockMode[1] & 0xff) << 8) | (blockMode[2] & 0xff);
        if (blockSize < flash.page_size || !acknowledge(new byte[]{'P'})) return -1;
        return 0;
    }

    @Override
    public int check_sig_bytes() {
        byte[] signature = exchange(new byte[]{'s'}, 3);
        // AVR109 sends the signature bytes in reverse order.
        signatureMatches = signature != null && signature[0] == config.signature[2]
                && signature[1] == config.signature[1] && signature[2] == config.signature[0];
        return signatureMatches ? 0 : -1;
    }

    @Override
    public int paged_read() {
        int size = flash.buf.length;
        if (!signatureMatches || size == 0 || size > flash.size) return -1;
        for (int address = 0; address < size; address += flash.page_size) {
            if (Thread.currentThread().isInterrupted()) return 0;
            int count = Math.min(flash.page_size, size - address);
            byte[] data = readBlock(address, count);
            if (data == null) return -1;
            System.arraycopy(data, 0, flash.buf, address, count);
            report_progress((address + count) * 100 / size);
        }
        return size;
    }

    @Override
    public int paged_write() {
        int size = flash.buf.length;
        if (!signatureMatches || size == 0 || size > APPLICATION_SIZE) return -1;
        byte[] page = new byte[flash.page_size];
        byte[] command = new byte[4 + page.length];
        command[0] = 'B';
        command[1] = (byte) (page.length >>> 8);
        command[2] = (byte) page.length;
        command[3] = 'F';

        for (int address = 0; address < size; address += page.length) {
            if (Thread.currentThread().isInterrupted()) {
                report_cancel();
                return 0;
            }
            int count = Math.min(page.length, size - address);
            Arrays.fill(page, (byte) 0xff);
            System.arraycopy(flash.buf, address, page, 0, count);
            System.arraycopy(page, 0, command, 4, page.length);
            if (!setAddress(address) || !acknowledge(command)
                    || !Arrays.equals(page, readBlock(address, page.length))) {
                if (Thread.currentThread().isInterrupted()) {
                    report_cancel();
                    return 0;
                }
                return -1;
            }
            report_progress((address + count) * 100 / size);
        }
        return size;
    }

    private byte[] readBlock(int address, int count) {
        if (!setAddress(address)) return null;
        return exchange(new byte[]{'g', (byte) (count >>> 8), (byte) count, 'F'}, count);
    }

    private boolean setAddress(int byteAddress) {
        int wordAddress = byteAddress / 2;
        return acknowledge(new byte[]{'A', (byte) (wordAddress >>> 8), (byte) wordAddress});
    }

    private boolean acknowledge(byte[] command) {
        byte[] response = exchange(command, 1);
        return response != null && response[0] == ACK;
    }

    private byte[] exchange(byte[] command, int length) {
        if (!atCommandBoundary || Thread.currentThread().isInterrupted()) return null;
        // A partial command may leave the bootloader waiting for payload bytes.
        atCommandBoundary = false;
        if (serial.write(command, command.length) != command.length) return null;
        byte[] response = new byte[length];
        byte[] fragment = new byte[length];
        int received = 0;
        long deadline = System.nanoTime() + REPLY_TIMEOUT_MS * 1_000_000L;
        while (received < length) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return null;
            int remaining = length - received;
            int count = serial.read(fragment, remaining);
            if (count < 0 || count > remaining) return null;
            if (count == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } else {
                System.arraycopy(fragment, 0, response, received, count);
                received += count;
            }
        }
        atCommandBoundary = true;
        return response;
    }

    @Override
    public void disable() {
        if (identified && atCommandBoundary) {
            acknowledge(new byte[]{'L'});
            acknowledge(new byte[]{'E'});
        }
        identified = false;
        signatureMatches = false;
    }
}
