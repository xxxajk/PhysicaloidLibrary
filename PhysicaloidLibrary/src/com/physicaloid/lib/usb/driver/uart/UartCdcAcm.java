/*
 * Copyright (C) 2013 Keisuke SUZUKI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * Distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.physicaloid.lib.usb.driver.uart;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.util.Log;
import com.physicaloid.BuildConfig;
import com.physicaloid.lib.Physicaloid;
import com.physicaloid.lib.UsbVidList;
import com.physicaloid.lib.framework.SerialCommunicator;
import com.physicaloid.lib.usb.UsbCdcConnection;
import com.physicaloid.lib.usb.UsbVidPid;
import com.physicaloid.misc.RingBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class UartCdcAcm extends SerialCommunicator {

        private static final String TAG = UartCdcAcm.class.getSimpleName();
        private boolean DEBUG_SHOW = false;
        private static final int DEFAULT_BAUDRATE = 9600;
        private UsbCdcConnection mUsbConnetionManager;
        private UartConfig mUartConfig;
        private static final int RING_BUFFER_SIZE = 1024;
        private static final int USB_READ_BUFFER_SIZE = 256;
        private static final int USB_WRITE_BUFFER_SIZE = 256;
        private RingBuffer mBuffer;
        private volatile Thread mReadThread;
        private UsbDeviceConnection mConnection;
        private UsbEndpoint mEndpointIn;
        private UsbEndpoint mEndpointOut;
        private int mInterfaceNum;
        private boolean isOpened;
        private byte[] wbuf = new byte[USB_WRITE_BUFFER_SIZE];
        private final Object DevLock = new Object();

        public UartCdcAcm(Context context) {
                super(context);
                mUsbConnetionManager = new UsbCdcConnection(context);
                mUartConfig = new UartConfig();
                mBuffer = new RingBuffer(RING_BUFFER_SIZE);
                isOpened = false;
        }

        @Override
        public boolean open() {
                if (isOpened) return true;
                for(UsbVidList id : UsbVidList.values()) {
                        if(open(new UsbVidPid(id.getVid(), 0))) {
                                return true;
                        }
                }
                return false;
        }

        public boolean open(UsbVidPid ids) {
                if (isOpened) return true;
                if(mUsbConnetionManager.open(ids, true)) {
                        mConnection = mUsbConnetionManager.getConnection();
                        mEndpointIn = mUsbConnetionManager.getEndpointIn();
                        mEndpointOut = mUsbConnetionManager.getEndpointOut();
                        mInterfaceNum = mUsbConnetionManager.getCdcAcmInterfaceNum();
                        if(mEndpointIn == null || mEndpointOut == null
                                || !init() || !setBaudrate(DEFAULT_BAUDRATE)) {
                                mUsbConnetionManager.close();
                                return false;
                        }
                        mBuffer.clear();
                        startRead();
                        isOpened = true;
                        return true;
                }
                return false;
        }

        @Override
        public boolean close() {
                stopRead();
                isOpened = false;
                return mUsbConnetionManager.close();
        }

        @Override
        public int read(byte[] buf, int size) {
                return mBuffer.get(buf, size);
        }

        @Override
        public int write(byte[] buf, int size) {
                if(buf == null) {
                        return 0;
                }
                int offset = 0;
                int write_size;
                int written_size;


                while(offset < size) {
                        write_size = USB_WRITE_BUFFER_SIZE;

                        if(offset + write_size > size) {
                                write_size = size - offset;
                        }
                        // optimization!
                        if(offset == 0) {
                                synchronized(DevLock) {
                                        written_size = mConnection.bulkTransfer(mEndpointOut, buf, write_size, 100);

                                }
                        } else {
                                System.arraycopy(buf, offset, wbuf, 0, write_size);
                                synchronized(DevLock) {
                                        written_size = mConnection.bulkTransfer(mEndpointOut, wbuf, write_size, 100);
                                }
                        }
                        if(written_size <= 0) {
                                return -1;
                        }
                        offset += written_size;
                }

                return offset;
        }

        private void stopRead() {
                synchronized (DevLock) {
                        mReadThread = null;
                }
        }

        private void startRead() {
                final UsbDeviceConnection connection = mConnection;
                final UsbEndpoint endpoint = mEndpointIn;
                mReadThread = new Thread(new Runnable() {
                        @Override public void run() {
                                readLoop(connection, endpoint);
                        }
                });
                mReadThread.start();
        }

        private String toHexStr(byte[] b, int length) {
                String str = "";
                for(int i = 0; i < length; i++) {
                        str += String.format("%02x ", b[i]);
                }
                return str;
        }
        private void readLoop(UsbDeviceConnection connection, UsbEndpoint endpoint) {
                try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
                } catch (SecurityException ignored) {
                }
                Thread reader = Thread.currentThread();
                byte[] bytes = new byte[endpoint.getMaxPacketSize()];
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                UsbRequest request = new UsbRequest();
                try {
                        if (!request.initialize(connection, endpoint)) return;
                        while (mReadThread == reader) {
                                buffer.clear();
                                if (!request.queue(buffer, bytes.length) || connection.requestWait() == null) {
                                        return;
                                }
                                int count = buffer.position();
                                synchronized (DevLock) {
                                        if (mReadThread != reader) return;
                                        if (count > 0) mBuffer.add(bytes, count);
                                }
                                if (count > 0) {
                                        if (DEBUG_SHOW) Log.d(TAG, "read(" + count + "): " + toHexStr(bytes, count));
                                        onRead(count);
                                }
                        }
                } finally {
                        request.close();
                }
        }

        /**
         * Sets Uart configurations
         *
         * @param config configurations
         *
         * @return true : successful, false : fail
         */
        public boolean setUartConfig(UartConfig config) {
                return setLineCoding(config.baudrate, config.stopBits, config.parity, config.dataBits)
                        && setDtrRts(config.dtrOn, config.rtsOn);
        }

        /**
         * Initializes CDC communication
         *
         * @return true : successful, false : fail
         */
        private boolean init() {
                if(mConnection == null) {
                        return false;
                }
                int ret = mConnection.controlTransfer(0x21, 0x22, 0x00, mInterfaceNum, null, 0, 100); // init CDC
                if(ret < 0) {
                        return false;
                }
                return true;
        }

        @Override
        public boolean isOpened() {
                return isOpened;
        }

        /**
         * Sets baudrate
         *
         * @param baudrate baudrate e.g. 9600
         *
         * @return true : successful, false : fail
         */
        public boolean setBaudrate(int baudrate) {
                return setLineCoding(baudrate, mUartConfig.stopBits, mUartConfig.parity, mUartConfig.dataBits);
        }

        private boolean setLineCoding(int baudrate, int stopBits, int parity, int dataBits) {
                byte[] coding = {(byte) baudrate, (byte) (baudrate >>> 8),
                        (byte) (baudrate >>> 16), (byte) (baudrate >>> 24),
                        (byte) stopBits, (byte) parity, (byte) dataBits};
                if (mConnection.controlTransfer(0x21, 0x20, 0, mInterfaceNum, coding, coding.length, 100)
                        != coding.length) {
                        if(DEBUG_SHOW) {
                                Log.d(TAG, "Failed to set CDC line coding");
                        }
                        return false;
                }
                mUartConfig.baudrate = baudrate;
                mUartConfig.stopBits = stopBits;
                mUartConfig.parity = parity;
                mUartConfig.dataBits = dataBits;
                return true;
        }

        /**
         * Sets Data bits
         *
         * @param dataBits data bits e.g. UartConfig.DATA_BITS8
         *
         * @return true : successful, false : fail
         */
        public boolean setDataBits(int dataBits) {
                return setLineCoding(mUartConfig.baudrate, mUartConfig.stopBits, mUartConfig.parity, dataBits);
        }

        /**
         * Sets Parity bit
         *
         * @param parity parity bits e.g. UartConfig.PARITY_NONE
         *
         * @return true : successful, false : fail
         */
        public boolean setParity(int parity) {
                return setLineCoding(mUartConfig.baudrate, mUartConfig.stopBits, parity, mUartConfig.dataBits);
        }

        /**
         * Sets Stop bits
         *
         * @param stopBits stop bits e.g. UartConfig.STOP_BITS1
         *
         * @return true : successful, false : fail
         */
        public boolean setStopBits(int stopBits) {
                return setLineCoding(mUartConfig.baudrate, stopBits, mUartConfig.parity, mUartConfig.dataBits);
        }

        @Override
        public boolean setDtrRts(boolean dtrOn, boolean rtsOn) {
                int ctrlValue = 0x0000;
                if(dtrOn) {
                        ctrlValue |= 0x0001;
                }
                if(rtsOn) {
                        ctrlValue |= 0x0002;
                }
                int ret = mConnection.controlTransfer(0x21, 0x22, ctrlValue, mInterfaceNum, null, 0, 100);
                if(ret < 0) {
                        if(DEBUG_SHOW) {
                                Log.d(TAG, "Fail to setDtrRts");
                        }
                        return false;
                }
                mUartConfig.dtrOn = dtrOn;
                mUartConfig.rtsOn = rtsOn;
                return true;
        }

        @Override
        public UartConfig getUartConfig() {
                return mUartConfig;
        }

        @Override
        public int getBaudrate() {
                return mUartConfig.baudrate;
        }

        @Override
        public int getDataBits() {
                return mUartConfig.dataBits;
        }

        @Override
        public int getParity() {
                return mUartConfig.parity;
        }

        @Override
        public int getStopBits() {
                return mUartConfig.stopBits;
        }

        @Override
        public boolean getDtr() {
                return mUartConfig.dtrOn;
        }

        @Override
        public boolean getRts() {
                return mUartConfig.rtsOn;
        }

        @Override
        public void clearBuffer() {
                mBuffer.clear();
        }
        //////////////////////////////////////////////////////////
        // Listener for reading uart
        //////////////////////////////////////////////////////////
        private List<ReadListener> uartReadListenerList = new ArrayList<ReadListener>();
        private volatile boolean mStopReadListener = false;

        @Override
        public void addReadListener(ReadListener listener) {
                uartReadListenerList.add(listener);
        }

        @Override
        @Deprecated
        public void addReadListener(ReadLisener listener) {
                addReadListener((ReadListener)listener);
        }

        @Override
        public void clearReadListener() {
                uartReadListenerList.clear();
        }

        @Override
        public void startReadListener() {
                mStopReadListener = false;
        }

        @Override
        public void stopReadListener() {
                mStopReadListener = true;
        }

        private void onRead(int size) {
                if(mStopReadListener) {
                        return;
                }
                for(ReadListener listener : uartReadListenerList) {
                        listener.onRead(size);
                }
        }
        //////////////////////////////////////////////////////////

        @Override
        public String getPhysicalConnectionName() {
                return Physicaloid.USB_STRING;
        }

        @Override
        public int getPhysicalConnectionType() {
                return Physicaloid.USB;
        }

        @Override
        public void setDebug(boolean flag) {
                DEBUG_SHOW = flag;
        }
}
