package com.physicaloid.lib.programmer.avr;
import com.physicaloid.lib.Boards;
import com.physicaloid.lib.framework.SerialCommunicator;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DownloaderTest {
    static class Protocol extends UploadProtocol {
        AVRMem memory; int failure, reads, leaves;
        public void setSerial(SerialCommunicator c) { }
        public void setConfig(AvrConf c, AVRMem m) {memory=m;}
        public int open(){return failure==1?-1:0;}
        public void enable(){ }
        public int initialize(){return failure==2?-1:0;}
        public int check_sig_bytes(){return failure==3?-1:0;}
        public int paged_write(){fail("Download must never program flash");return -1;}
        public int paged_read(){reads++;if(failure==5)Thread.currentThread().interrupt();return failure==4?0:memory.buf.length;}
        public void disable(){leaves++;}
    }
    @Test public void successAndFailureAlwaysLeaveProgrammingMode() throws Exception {
        for(int fault=0;fault<6;fault++) {
            final Protocol protocol=new Protocol();protocol.failure=fault;
            AvrDownloader downloader=new AvrDownloader(new FakeSerial()) {
                @Override protected UploadProtocol protocol(Boards b){return protocol;}
            };
            try {
                byte[] data=downloader.readFlash(Boards.ARDUINO_UNO,null);
                assertEquals(0,fault);assertEquals(32768,data.length);
            } catch(IOException expected) { assertTrue(fault>0); }
            finally {Thread.interrupted();}
            assertEquals(1,protocol.leaves);
            if(fault>=1 && fault<=3)assertEquals(0,protocol.reads);
        }
    }
    @Test public void unsupportedBoardNeverTouchesSerial() throws Exception {
        FakeSerial serial=new FakeSerial();
        try {new AvrDownloader(serial).readFlash(Boards.PERIDOT,null);fail();}
        catch(IOException expected){assertTrue(serial.writes.isEmpty());}
    }
}
