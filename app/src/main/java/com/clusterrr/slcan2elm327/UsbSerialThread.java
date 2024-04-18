package com.clusterrr.slcan2elm327;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class UsbSerialThread extends Thread {
    private final static int WRITE_TIMEOUT = 1000;
    private Service service;
    private UsbSerialPort serialPort;
    private byte[] buffer;
    private int woff, roff;
    private boolean running;

    public UsbSerialThread(Service service) {
        this.service = service;
        serialPort = null;
        buffer = new byte[1024];
        woff = roff = 0;
        running = true;
    }

    public void reset() {
        woff = roff = 0;
        /* Config - 500 kilobits/s */
        write("\r\r\r\rC\rS6\rM0\rA1\rO\r");
    }

    @Override
    public void run() {
        byte buffer[] = new byte[32];
        try {
            UsbManager manager = (UsbManager) service.getSystemService(Context.USB_SERVICE);
            while (running) {
                try {
                    List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
                    if (availableDrivers.size() == 0) {
                        service.statusUpdateUsb(service.getString(R.string.usb_wait));
                        Thread.sleep(1000);
                        continue;
                    }
                    UsbSerialDriver driver = availableDrivers.get(0); // Open a connection to the first available driver.
                    UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                    if (connection == null) {
                        service.statusUpdateUsb(service.getString(R.string.usb_permission));
                        Thread.sleep(1000);
                        continue;
                    }
                    serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
                    serialPort.open(connection);
                    serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                    service.statusUpdateUsb(service.getString(R.string.usb_connected));
                    reset();
                    while (running) {
                        int l = serialPort.read(buffer, 0);
                        if (l <= 0) break; // disconnect
                        try {
                            System.arraycopy(buffer, 0, this.buffer, woff, l);
                            woff += l;
                            proceedBuffer();
                        } catch (Exception e) {
                            e.printStackTrace();
                            reset();
                        }
                    }
                    serialPort.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void proceedBuffer() {
        for (int i=roff;i < woff ; ++i) {
            if ((char)buffer[i] == '\r') {
                String command = new String(buffer, roff, i-roff, StandardCharsets.ISO_8859_1);
                CANFrame f = CANFrame.fromSLCAN(command);
                if(f != null) {
                    service.threadElm.proceedCAN(f);
                    service.threadNet.proceedCAN(f);
                }
                /* Remove the used data and continue */
                roff = i+1;
                if(woff == roff) woff = roff = 0;
            }
        }
    }

    private void write(String s) {
        try {
            serialPort.write(s.getBytes(StandardCharsets.ISO_8859_1), WRITE_TIMEOUT);
        } catch (IOException e) {
            service.statusUpdateUsb(service.getString(R.string.usb_error));
        }
    }

    public void sendCAN(CANFrame f) {
        if(serialPort != null) write(f.toSLCAN());
    }

    public void close() {
        try {
            service.statusUpdateUsb(service.getString(R.string.usb_stopping));
            running = false;
            if(serialPort != null) {
                serialPort.close();
                serialPort = null;
            }
            join();
            service.statusUpdateUsb(service.getString(R.string.usb_stopped));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
