Physicaloid Library
==================

Android Library for communicating with physical-computing boards (e.g.Arduino, mbed)

Features
-----------------
- **NEW! No closed source slow and buggy D2XX drivers are required.**
- Android Java library project
- USB-Serial communication
- WiFi-Serial communication to ESP8266
- upload firmware to an Arduino
- read AVR flash back as raw bytes or Intel HEX (STK500v1/v2)
- experimental native Urboot ATmega328P readback and verified programming
- support on Android 4.4 / API 19 or higher (USB connections need USB Host API)
- **does not require ROOT**
- supports USB-Serial protocols : CDC-ACM, FTDI, Silicon Labs CP210x and WinChipHead CH34X
- supports uploading firmware protocols : STK500, STK500V2, Binary blob ESP8266 OTA
- open-source(Apache License 2.0)


![Android x Arduino](https://lh5.googleusercontent.com/-weC-lA-1rdw/UeaCzIrWR3I/AAAAAAAACno/u-ZapAmzkz8/s640/android_arduino.jpg)


Users do not need to download an Arduino sketch from a web site.
![Download sketch](https://lh3.googleusercontent.com/-Hh-vISkTL6w/UeaC5moml2I/AAAAAAAACn8/g7Dozio1QrE/s640/physicaloid_download.png)


You (developer) can include Arduino firmwares in your Android app and upload to Google Play.
![Upload to Google Play](https://lh6.googleusercontent.com/-lzDrLOSohUY/UeaC5p7Z0uI/AAAAAAAACoA/hcqRjLUe6JQ/s640/physicaloid_upload.png)


Code example
-----------------

### Upload a firmware from Android to Arduino ###
```java
Physicaloid mPhysicaloid = new Physicaloid(this);
mPhysicaloid.upload(Boards.ARDUINO_UNO, "/sdcard/arduino/Blink.hex");
```


### Write serial data to Arduino ###
```java
Physicaloid mPhysicaloid = new Physicaloid(this);
if(mPhysicaloid.open()) {
        byte[] buf = "moemoe".getBytes();
        mPhysicaloid.write(buf, buf.length);
        mPhysicaloid.close()
}
```


### Read serial data from Arduino ###
```java
Physicaloid mPhysicaloid = new Physicaloid(this);
TextView TextView1 = (TextView) findViewById(R.id.TextView1);// Android TextView

if(mPhysicaloid.open()) {
        byte[] buf = new byte[256];

        mPhysicaloid.read(buf, buf.length);
        String str = new String(buf);
        TextView1.append(str);

        mPhysicaloid.close();
}
```

How to use
-----------------
Import this repository in Android Studio, or add the built library AAR to your app.
The original Ant/Eclipse files are retained for historical users; CI uses Gradle.

### Build and download artifacts

Use JDK 17, Android SDK platform 35 and build-tools 34.0.0. Set ANDROID_HOME or
sdk.dir in local.properties, then run (gradlew.bat on Windows):

```sh
./gradlew :PhysicaloidLibrary:testDebugUnitTest :PhysicaloidLibrary:assembleRelease :SampleProjects:PhysicaloidTest:assembleDebug
```

GitHub Actions runs this command on pushes, pull requests, and manual dispatches.
Open the repository's Actions tab, select a successful **Android build and tests**
run, and download the commit-named library AAR, sample debug APK, or test reports
from **Artifacts**. Retention is 30 days. There is no external temporary-file upload.
The sample retains target API 28 for its legacy permission behavior; it is a debug
demo, not a modern Play Store release. The library can be used by newer-target apps,
which must handle their platform's USB/Bluetooth/network permissions themselves.

### Read flash back to Android

Open the connection with the same permission flow used for serial communication,
then run the blocking read on a worker thread. For example, inside an Activity:

```java
final Physicaloid device = new Physicaloid(this);
if (device.open()) {
    new Thread(new Runnable() {
        @Override public void run() {
            try {
                // Only save after the entire flash has been read successfully.
                byte[] flash = device.readFlash(Boards.ARDUINO_UNO);
                try (java.io.FileOutputStream out = openFileOutput("backup.hex", MODE_PRIVATE)) {
                    com.physicaloid.lib.programmer.avr.IntelHexWriter.write(flash, out);
                }
            } catch (java.io.IOException e) {
                android.util.Log.e("Readback", "Backup failed", e);
            } finally {
                device.close();
            }
        }
    }).start();
}
```

Alternatively, use download(board, outputStream, listener) to export Intel HEX or
readFlash(board, listener) for raw bytes and progress. The caller owns the output
stream. Callbacks run on the worker thread; post UI changes to the main thread.
Interrupt the worker to cancel. Readback serializes with uploads and serial I/O,
temporarily stops read notifications, and restores the UART configuration and
notifications in finally. An already open connection remains open.

Readback resets the MCU into its bootloader but sends no flash program or erase
command. It reads the full configured flash, including any bootloader-visible
bytes. Lock bits and bootloader implementations may restrict access or return
masked bytes; success means the protocol returned all bytes, not that protected
memory was bypassed. No EEPROM/fuse/lock-byte backup is provided. Supported AVR
configurations are ATmega168, ATmega328P, ATmega1284P and ATmega2560; other historical
board enums without an AVR configuration are still unsupported. A full-chip backup
is not automatically safe to upload through a bootloader: exclude its protected area.

### Experimental Urboot / Urclock

Use Boards.URBOOT_328P for native urprotocol on an ATmega328P at 115200 baud
(including compatible autobaud builds). It supports both readFlash/download and
the existing upload API. This profile remains marked experimental and is not
included in the sample's tested-board picker.

The implementation confirms a stable sync pair, checks MCU ID 119 and flash-read
capability, uses byte-addressed native commands, and verifies each written page.
Programming requires a readable Urboot v7.5-v8.0 table, a hardware boot section,
and page-erase-before-write behavior. It rejects vector bootloaders, NOR-style
programming, unknown table versions, and images overlapping the bootloader before
issuing any write. A partial final page is padded with 0xff; flash beyond the
image's final page is left unchanged. EEPROM, chip erase, vector patching, and
AVRDUDE metadata handling are not implemented. STK-compatible Urboot builds use
the ordinary STK500 board profile instead.

This is a bounded Java implementation of the [published urprotocol](https://github.com/stefanrueger/urboot/blob/main/urprotocol.md),
not a full AVRDUDE urclock port. Protocol tests run against simulated serial
devices. Physical-board/Android USB acceptance remains to be performed.


Special Thanks
-----------------
This code has built in knowledge of avrdude.
Thanks to all avrdude coders.


License
-----------------
Physicaloid Library is released under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
