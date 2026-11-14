package com.physicaloid.lib.programmer.avr;

import com.physicaloid.lib.Boards;
import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadbackTest {
    static byte pattern(int address) { return (byte)(address ^ (address >> 8) ^ (address >> 16)); }
    static AVRMem configure(UploadProtocol protocol, FakeSerial serial, Boards board, int length) throws Exception {
        AvrConf config = new AvrConf(board);
        AVRMem mem = new AVRMem(config); mem.buf = new byte[length];
        protocol.setConfig(config, mem); protocol.setSerial(serial); return mem;
    }
    static class V1 extends FakeSerial {
        int address;
        boolean badStatus, truncated;
        @Override void respond(byte[] c) {
            if (c[0] == 0x55) {
                address = ((c[1]&255) | (c[2]&255)<<8)*2;
                reply(new byte[]{0x14,0x10});
            } else if (c[0] == 0x74) {
                int n = (c[1]&255)*256+(c[2]&255);
                byte[] response = new byte[n+2]; response[0]=0x14;
                for (int i=0;i<n;i++) response[i+1]=pattern(address+i);
                response[n+1]=(byte)(badStatus?0x11:0x10);
                reply(truncated?Arrays.copyOf(response, n):response);
            } else fail("Unexpected/destructive command: " + c[0]);
        }
    }
    @Test public void v1FragmentedFullFlash() throws Exception {
        V1 serial = new V1(); Stk500 p = new Stk500();
        AVRMem m = configure(p,serial,Boards.BALANDUINO,131072);
        assertEquals(m.buf.length,p.paged_read());
        for(int i=0;i<m.buf.length;i++) assertEquals(pattern(i),m.buf[i]);
    }
    @Test public void v1RejectsBadStatusAndTruncation() throws Exception {
        for(boolean truncate:new boolean[]{false,true}) {
            V1 s=new V1(); s.badStatus=!truncate; s.truncated=truncate;
            Stk500 p=new Stk500(); configure(p,s,Boards.ARDUINO_UNO,128);
            assertEquals(-1,p.paged_read());
        }
    }
    @Test public void v1PartialPageAndShortWrite() throws Exception {
        V1 s=new V1(); Stk500 p=new Stk500();
        AVRMem m=configure(p,s,Boards.ARDUINO_UNO,129);
        assertEquals(129,p.paged_read()); assertEquals(pattern(128),m.buf[128]);
        s.shortWrite=true; assertEquals(-1,p.paged_read());
    }
    static class V2 extends FakeSerial {
        int address; boolean badChecksum, badStatus, badCommand, truncated;
        @Override void respond(byte[] c) {
            assertEquals(0x1b,c[0]); assertEquals(0x0e,c[4]);
            byte checksum=0; for(byte b:c) checksum^=b; assertEquals(0,checksum);
            byte[] body;
            if(c[5]==6) {
                assertEquals(0x80,c[6]&255);
                address=((c[7]&255)<<16 | (c[8]&255)<<8 | (c[9]&255))*2;
                body=new byte[]{6,0};
            } else if(c[5]==0x14) {
                int n=(c[6]&255)*256+(c[7]&255);
                assertEquals(0x20,c[8]);
                body=new byte[n+3];body[0]=(byte)(badCommand?0x15:0x14);
                for(int i=0;i<n;i++) body[i+2]=pattern(address+i);
                body[n+2]=(byte)(badStatus?1:0);
                if(truncated) body=Arrays.copyOf(body,body.length-1);
            } else { fail("Unexpected/destructive command"); return; }
            byte[] frame=new byte[body.length+6]; frame[0]=0x1b;frame[1]=c[1];
            frame[2]=(byte)(body.length>>8);frame[3]=(byte)body.length;frame[4]=0x0e;
            System.arraycopy(body,0,frame,5,body.length);
            for(int i=0;i<frame.length-1;i++) frame[frame.length-1]^=frame[i];
            if(badChecksum) frame[frame.length-1]^=1;
            reply(frame);
        }
    }
    @Test public void v2ReadsAcross64kAnd128kBoundaries() throws Exception {
        V2 s=new V2(); Stk500V2 p=new Stk500V2();
        AVRMem m=configure(p,s,Boards.ARDUINO_MEGA_2560_ADK,262144);
        assertEquals(262144,p.paged_read());
        for(int i=0;i<m.buf.length;i++) assertEquals(pattern(i),m.buf[i]);
    }
    @Test public void v2RejectsMalformedReplies() throws Exception {
        for(int fault=0;fault<5;fault++) {
            V2 s=new V2();s.badChecksum=fault==0;s.badStatus=fault==1;
            s.badCommand=fault==2;s.truncated=fault==3;s.shortWrite=fault==4;
            Stk500V2 p=new Stk500V2();configure(p,s,Boards.ARDUINO_MEGA_2560_ADK,256);
            assertEquals(-1,p.paged_read());
        }
    }
    @Test public void cancellationSendsNoReadCommands() throws Exception {
        for(UploadProtocol p:new UploadProtocol[]{new Stk500(),new Stk500V2()}) {
            FakeSerial s=new FakeSerial();configure(p,s,Boards.ARDUINO_UNO,128);
            Thread.currentThread().interrupt();
            try { assertEquals(0,p.paged_read()); assertTrue(s.writes.isEmpty()); }
            finally { Thread.interrupted(); }
        }
    }
    @Test public void hexChecksumsExtendedAddressesAndEof() throws Exception {
        byte[] data=new byte[262144];for(int i=0;i<data.length;i++) data[i]=pattern(i);
        ByteArrayOutputStream out=new ByteArrayOutputStream();IntelHexWriter.write(data,out);
        String[] lines=out.toString("US-ASCII").split("\n");
        int upper=0, count=0;
        for(String line:lines) {
            int sum=0; for(int i=1;i<line.length();i+=2) sum+=Integer.parseInt(line.substring(i,i+2),16);
            assertEquals(0,sum&255);
            int n=Integer.parseInt(line.substring(1,3),16),type=Integer.parseInt(line.substring(7,9),16);
            if(type==4) upper=Integer.parseInt(line.substring(9,13),16)<<16;
            if(type==0) {
                int address=upper+Integer.parseInt(line.substring(3,7),16);
                assertEquals(count,address);
                for(int i=0;i<n;i++) assertEquals(data[count++]&255,Integer.parseInt(line.substring(9+i*2,11+i*2),16));
            }
        }
        assertEquals(data.length,count);assertEquals(":00000001FF",lines[lines.length-1]);
    }
    @Test public void hexPartialFinalRecordAndOutputFailure() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();IntelHexWriter.write(new byte[]{1,2,3},out);
        assertEquals(":03000000010203F7\n:00000001FF\n",out.toString("US-ASCII"));
        try { IntelHexWriter.write(new byte[1],new OutputStream(){ public void write(int b)throws IOException{throw new IOException();}});fail(); }
        catch(IOException expected) { }
    }
}
