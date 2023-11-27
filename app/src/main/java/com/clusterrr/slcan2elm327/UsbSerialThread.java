package com.clusterrr.slcan2elm327;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

public class UsbSerialThread extends Thread {
    final static int WRITE_TIMEOUT = 1000;
    private Service service;
    private UsbSerialPort serialPort;
    private UsbManager manager;
    private int baudRate, dataBits, stopBits, parity;
    private byte[] buffer;
    private int woff, roff;
    private boolean running;

    public UsbSerialThread(Service service, int baudRate, int dataBits, int stopBits, int parity) {
        this.service = service;
        this.manager = (UsbManager) service.getSystemService(Context.USB_SERVICE);
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        serialPort = null;
        buffer = new byte[1024];
        woff = roff = 0;
        running = true;
    }

    public void reset(){
        woff = roff = 0;
        try {
            /* Config - 500 kilobits/s */
            write("\r\r\r\rC\rS6\rM0\rA1\rO\r");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        byte buffer[] = new byte[32];
        try {
            while (running) {
                try {
                    List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
                    if (availableDrivers.size() == 0) {
                        service.statusUpdateUSB(service.getString(R.string.usb_wait));
                        Thread.sleep(1000);
                        continue;
                    }
                    UsbSerialDriver driver = availableDrivers.get(0); // Open a connection to the first available driver.
                    UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                    if (connection == null) {
                        service.statusUpdateUSB(service.getString(R.string.usb_permission));
                        Thread.sleep(1000);
                        continue;
                    }
                    serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
                    serialPort.open(connection);
                    serialPort.setParameters(baudRate, dataBits, stopBits, parity);
                    service.statusUpdateUSB(service.getString(R.string.usb_connected));
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

    private void proceedBuffer() throws IOException, InterruptedException {
        StringBuilder command = new StringBuilder(64);

        for (int i=roff;i < woff ; ++i) {
            char b = (char)buffer[i];
            /* Ignore space */
            if(b == ' ') continue;
            if(b == '\r'){
                CANFrame f = CANFrame.fromSLCAN(command);
                if(f != null) service.threadNET.proceedCAN(f);
                /* Remove the used data and continue */
                command.setLength(0);
                roff = i+1;
                if(woff == roff) woff = roff = 0;
            }else{
                command.append(b);
            }
        }
    }

    public void write(String s) throws IOException {
        if(serialPort != null)
            serialPort.write(s.getBytes(), WRITE_TIMEOUT);
    }

    public void close() {
        try {
            service.statusUpdateUSB(service.getString(R.string.usb_stopping));
            running = false;
            if(serialPort != null){
                serialPort.close();
                serialPort = null;
            }
            join();
            service.statusUpdateUSB(service.getString(R.string.usb_stopped));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
