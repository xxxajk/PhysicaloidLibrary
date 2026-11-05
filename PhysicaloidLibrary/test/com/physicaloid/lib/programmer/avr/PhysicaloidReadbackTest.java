package com.physicaloid.lib.programmer.avr;
import com.physicaloid.lib.*;
import com.physicaloid.lib.framework.SerialCommunicator;
import com.physicaloid.lib.usb.driver.uart.UartConfig;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhysicaloidReadbackTest {
    static class Device extends Physicaloid {
        Device(SerialCommunicator serial) {super(null);mSerial=serial;}
    }
    static class Port extends UrclockTest.Bootloader {
        UartConfig config=new UartConfig();
        int stops,starts,closes;
        public UartConfig getUartConfig(){return config;}
        public boolean setUartConfig(UartConfig value){config=value;return true;}
        public void stopReadListener(){stops++;}
        public void startReadListener(){starts++;}
        public boolean close(){closes++;return true;}
    }
    @Test public void publicApiRestoresConnectionAndReportsProgress() throws Exception {
        Port port=new Port();port.config.baudrate=9600;
        final int[] progress={0};
        byte[] result=new Device(port).readFlash(Boards.URBOOT_328P,new Physicaloid.ReadbackListener(){
            public void onReading(int value){assertTrue(value>=progress[0]);progress[0]=value;}
        });
        assertArrayEquals(port.flash,result);assertEquals(100,progress[0]);
        assertEquals(9600,port.config.baudrate);assertEquals(1,port.stops);
        assertEquals(1,port.starts);assertEquals(0,port.closes);assertEquals(0,port.programs);
    }
    @Test public void failedDownloadDoesNotWriteOutputAndRestoresUart() throws Exception {
        Port port=new Port();port.mcuid=120;port.config.baudrate=19200;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try {new Device(port).download(Boards.URBOOT_328P,out,null);fail();}
        catch(IOException expected){ }
        assertEquals(0,out.size());assertEquals(19200,port.config.baudrate);
        assertEquals(1,port.starts);assertEquals(0,port.closes);
    }
    @Test public void uploaderUsesNativeUrbootAndLeavesOnFailure() throws Exception {
        for(boolean corrupt:new boolean[]{false,true}) {
            Port port=new Port();port.corruptVerify=corrupt;
            ByteArrayOutputStream hex=new ByteArrayOutputStream();
            IntelHexWriter.write(new byte[]{1,2,3,4},hex);
            boolean success=new AvrUploader(port).run(new ByteArrayInputStream(hex.toByteArray()),Boards.URBOOT_328P,null);
            assertEquals(!corrupt,success);assertEquals(1,port.programs);
            assertEquals(0x51,port.writes.get(port.writes.size()-1)[0]);
        }
    }
}
