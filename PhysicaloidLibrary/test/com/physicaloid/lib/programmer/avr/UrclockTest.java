package com.physicaloid.lib.programmer.avr;
import com.physicaloid.lib.Boards;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class UrclockTest {
    static class Bootloader extends FakeSerial {
        byte[] flash = new byte[32768];
        int mcuid=119, features=4, syncCount, programs;
        boolean corruptVerify;
        Bootloader() {
            for(int i=0;i<flash.length;i++) flash[i]=ReadbackTest.pattern(i);
            flash[32762]=2; flash[32763]=0; flash[32766]=2; flash[32767]=64;
        }
        byte inSync() { return (byte)((features*2040+mcuid)/255); }
        byte ok() { int low=(features*2040+mcuid)%255;return (byte)(low>= (inSync()&255)?low+1:low); }
        @Override void respond(byte[] c) {
            assertEquals(0x20,c[c.length-1]);
            if(c[0]==0x30) { syncCount++;reply(new byte[]{inSync(),ok()}); }
            else if(c[0]==3) {
                int addr=(c[1]&255)+(c[2]&255)*256,n=c[3]&255;if(n==0)n=256;
                assertTrue(addr+n<=flash.length);
                byte[] response=new byte[n+2];response[0]=inSync();response[n+1]=ok();
                System.arraycopy(flash,addr,response,1,n);
                if(corruptVerify && programs>0)response[1]^=1;
                reply(response);
            } else if(c[0]==2) {
                int addr=(c[1]&255)+(c[2]&255)*256;
                assertEquals(133,c.length);assertEquals(128,c[3]&255);
                assertTrue(addr+128<=32512);programs++;
                System.arraycopy(c,4,flash,addr,128);reply(new byte[]{inSync(),ok()});
            } else if(c[0]==0x51) reply(new byte[]{inSync(),ok()});
            else fail("Unexpected command " + c[0]);
        }
    }
    @Test public void nativeReadbackAndIdentity() throws Exception {
        Bootloader s=new Bootloader();Urclock p=new Urclock();
        AVRMem m=ReadbackTest.configure(p,s,Boards.URBOOT_328P,32768);
        assertEquals(0,p.open());assertTrue(s.syncCount>=2);assertEquals(0,p.check_sig_bytes());
        assertEquals(32768,p.paged_read());assertArrayEquals(s.flash,m.buf);assertEquals(0,s.programs);
        p.disable();assertEquals(-1,p.initialize());
    }
    @Test public void rejectWrongChipAndMissingReadCapability() throws Exception {
        for(int fault=0;fault<2;fault++) {
            Bootloader s=new Bootloader();if(fault==0)s.mcuid=120;else s.features=0;
            Urclock p=new Urclock();ReadbackTest.configure(p,s,Boards.URBOOT_328P,128);
            assertEquals(-1,p.open());assertEquals(-1,p.paged_write());assertEquals(0,s.programs);
        }
    }
    @Test public void programPadsPartialPageAndVerifies() throws Exception {
        Bootloader s=new Bootloader();Urclock p=new Urclock();
        AVRMem m=ReadbackTest.configure(p,s,Boards.URBOOT_328P,129);Arrays.fill(m.buf,(byte)0x5a);
        assertEquals(0,p.open());assertEquals(129,p.paged_write());assertEquals(2,s.programs);
        assertEquals(0x5a,s.flash[128]);assertEquals((byte)0xff,s.flash[129]);
        assertEquals(2,s.flash[32762]);
    }
    @Test public void rejectUnsafeProgrammingProfilesBeforeWriting() throws Exception {
        for(int fault=0;fault<7;fault++) {
            Bootloader s=new Bootloader();
            if(fault==0)s.features|=8;
            if(fault==1)s.flash[32766]|=4;
            if(fault==2)s.flash[32763]=1;
            if(fault==3)s.flash[32762]=0;
            if(fault==4)s.flash[32767]=65;
            if(fault==5)s.flash[32762]=(byte)128;
            Urclock p=new Urclock();ReadbackTest.configure(p,s,Boards.URBOOT_328P,fault==6?32640:128);
            assertEquals(0,p.open());assertEquals(-1,p.paged_write());assertEquals(0,s.programs);
        }
    }
    @Test public void failedVerificationIsNotSuccess() throws Exception {
        Bootloader s=new Bootloader();s.corruptVerify=true;Urclock p=new Urclock();
        ReadbackTest.configure(p,s,Boards.URBOOT_328P,128);
        assertEquals(0,p.open());assertEquals(-1,p.paged_write());
    }
    @Test public void cancelledBeforeSync() throws Exception {
        Bootloader s=new Bootloader();Urclock p=new Urclock();ReadbackTest.configure(p,s,Boards.URBOOT_328P,128);
        Thread.currentThread().interrupt();
        try { assertEquals(-1,p.open());assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,s.programs); }
        finally {Thread.interrupted();}
    }
}
