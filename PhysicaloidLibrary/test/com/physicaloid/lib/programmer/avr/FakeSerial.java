package com.physicaloid.lib.programmer.avr;
import com.physicaloid.lib.framework.SerialCommunicator;
import com.physicaloid.lib.usb.driver.uart.*;
import java.util.*;
class FakeSerial extends SerialCommunicator {
    final Queue<Byte> input = new ArrayDeque<Byte>();
    final List<byte[]> writes = new ArrayList<byte[]>();
    int fragment = 7;
    boolean shortWrite;
    FakeSerial() { super(null); }
    void reply(byte[] data) { for (byte b : data) input.add(b); }
    public int read(byte[] buf, int size) {
        int n = Math.min(Math.min(fragment, size), input.size());
        for (int i=0; i<n; i++) buf[i] = input.remove();
        return n;
    }
    public int write(byte[] buf, int size) {
        byte[] command = Arrays.copyOf(buf, size); writes.add(command);
        if (shortWrite) return size-1;
        respond(command);
        return size;
    }
    void respond(byte[] command) { }
    public boolean open() { return true; }
    public boolean close() { return true; }
    public boolean isOpened() { return true; }
    public boolean setUartConfig(UartConfig config) { return true; }
    public boolean setBaudrate(int baudrate) { return true; }
    public boolean setDataBits(int dataBits) { return true; }
    public boolean setParity(int parity) { return true; }
    public boolean setStopBits(int stopBits) { return true; }
    public boolean setDtrRts(boolean dtrOn, boolean rtsOn) { return true; }
    public UartConfig getUartConfig() { return new UartConfig(); }
    public int getBaudrate() { return 0; }
    public int getDataBits() { return 0; }
    public int getParity() { return 0; }
    public int getStopBits() { return 0; }
    public boolean getDtr() { return true; }
    public boolean getRts() { return true; }
    public void addReadListener(ReadListener listener) {  }
    public void addReadListener(ReadLisener listener) {  }
    public void clearReadListener() {  }
    public void startReadListener() {  }
    public void stopReadListener() {  }
    public void clearBuffer() {  }
    public String getPhysicalConnectionName() { return "fake"; }
    public int getPhysicalConnectionType() { return 0; }
    public void setDebug(boolean flag) {  }
}
