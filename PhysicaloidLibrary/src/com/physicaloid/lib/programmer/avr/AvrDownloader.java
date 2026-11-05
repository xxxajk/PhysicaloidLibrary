package com.physicaloid.lib.programmer.avr;

import com.physicaloid.lib.Boards;
import com.physicaloid.lib.Physicaloid.UploadCallBack;
import com.physicaloid.lib.framework.SerialCommunicator;
import java.io.IOException;
import java.io.InterruptedIOException;

/** Reads flash only: never calls erase or paged_write. Caller owns the serial connection. */
public class AvrDownloader {
    private final SerialCommunicator serial;

    public AvrDownloader(SerialCommunicator serial) { this.serial = serial; }

    protected UploadProtocol protocol(Boards board) throws IOException {
        if (board.uploadProtocol == Boards.UploadProtocols.URCLOCK) return new Urclock();
        if (board.uploadProtocol == Boards.UploadProtocols.STK500) return new Stk500();
        if (board.uploadProtocol == Boards.UploadProtocols.STK500V2) return new Stk500V2();
        throw new IOException("Flash readback is unsupported for this board protocol");
    }

    /** Returns a full-chip flash image only after every page has been received successfully. */
    public byte[] readFlash(Boards board, UploadCallBack progress) throws IOException {
        if (board == null || serial == null) throw new IllegalArgumentException("board/serial is null");
        UploadProtocol programmer = protocol(board);
        AvrConf config;
        try { config = new AvrConf(board); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Flash readback cancelled");
        }
        AVRMem memory = new AVRMem(config);
        memory.buf = new byte[memory.size];
        programmer.setSerial(serial);
        programmer.setConfig(config, memory);
        programmer.setCallback(progress);
        try {
            cancelled();
            if (programmer.open() < 0) throw new IOException("Cannot synchronize with MCU");
            programmer.enable();
            cancelled();
            if (programmer.initialize() < 0) throw new IOException("Cannot initialize MCU");
            if (programmer.check_sig_bytes() != 0) throw new IOException("Incorrect MCU signature");
            cancelled();
            int count = programmer.paged_read();
            cancelled();
            if (count != memory.size) throw new IOException("Incomplete flash readback");
            return memory.buf;
        } finally {
            // Clear cancellation temporarily so the leave-programming command can complete.
            boolean interrupted = Thread.interrupted();
            try { programmer.disable(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Flash readback cancelled");
    }
}
