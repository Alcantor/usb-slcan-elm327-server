package com.clusterrr.slcan2elm327;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Lotus {
    private final Service service;
    private final BlockingQueue<CANFrame> rxqueue;
    private final int READ_TIMEOUT = 1000;

    public Lotus(Service service) {
        this.service = service;
        rxqueue = new ArrayBlockingQueue<>(8);
    }

    public byte [] readMemory(int address, int size) throws IOException, InterruptedException {
        ByteBuffer buffer;
        CANFrame frame;
        if (size == 4) {
            buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            frame = new CANFrame(0x50, false, false, buffer.array());
            rxqueue.clear();
            service.threadUsb.sendCAN(frame);
            frame = rxqueue.poll(READ_TIMEOUT, TimeUnit.MILLISECONDS);
            if (frame == null) throw new IOException("ECU Read Word failed!");
            if (frame.data.length != 4) throw new IOException("Unexpected answer!");
            return frame.data;
        }else if (size == 2) {
            buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            frame = new CANFrame(0x51, false, false, buffer.array());
            rxqueue.clear();
            service.threadUsb.sendCAN(frame);
            frame = rxqueue.poll(READ_TIMEOUT, TimeUnit.MILLISECONDS);
            if (frame == null) throw new IOException("ECU Read Half failed!");
            if (frame.data.length != 2) throw new IOException("Unexpected answer!");
            return frame.data;
        }else if (size == 1) {
            buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            frame = new CANFrame(0x52, false, false, buffer.array());
            rxqueue.clear();
            service.threadUsb.sendCAN(frame);
            frame = rxqueue.poll(READ_TIMEOUT, TimeUnit.MILLISECONDS);
            if (frame == null) throw new IOException("ECU Read Byte failed!");
            if (frame.data.length != 1) throw new IOException("Unexpected answer!");
            return frame.data;
        }else if (size < 256) {
            int offset = 0;
            buffer = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            buffer.put((byte) size);
            frame = new CANFrame(0x53, false, false, buffer.array());
            rxqueue.clear();
            service.threadUsb.sendCAN(frame);
            byte[] data = new byte[size];
            while(size > 0) {
                int chunk_size = Math.min(size, 8);
                frame = rxqueue.poll(READ_TIMEOUT, TimeUnit.MILLISECONDS);
                if (frame == null) throw new IOException("ECU Read Buffer failed!");
                if (frame.data.length != chunk_size) throw new IOException("Unexpected answer!");
                System.arraycopy(frame.data, 0, data, offset, chunk_size);
                offset += chunk_size;
                size -= chunk_size;
            }
            return data;
        } else {
            throw new IOException("ECU Read too much bytes!");
        }
    }

    public void writeMemory(int address, byte [] data, boolean verify) throws IOException, InterruptedException {
        ByteBuffer buffer;
        CANFrame frame;
        int size = data.length;
        if (size == 4) {
            buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            buffer.put(data);
            frame = new CANFrame(0x54, false, false, buffer.array());
            service.threadUsb.sendCAN(frame);
        } else if (size == 2) {
            buffer = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            buffer.put(data);
            frame = new CANFrame(0x55, false, false, buffer.array());
            service.threadUsb.sendCAN(frame);
        } else if (size == 1) {
            buffer = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            buffer.put(data);
            frame = new CANFrame(0x56, false, false, buffer.array());
            service.threadUsb.sendCAN(frame);
        } else if (size < 256) {
            int offset = 0;
            buffer = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(address);
            buffer.put((byte) size);
            frame = new CANFrame(0x57, false, false, buffer.array());
            service.threadUsb.sendCAN(frame);
            while(size > 0) {
                int chunk_size = Math.min(size, 8);
                frame = new CANFrame(0x57, false, false, new byte[chunk_size]);
                System.arraycopy(data, offset, frame.data, 0, chunk_size);
                service.threadUsb.sendCAN(frame);
                offset += chunk_size;
                size -= chunk_size;
            }
        } else {
            throw new IOException("ECU Write too much bytes!");
        }
        if(verify && !Arrays.equals(data, readMemory(address, data.length)))
            throw new IOException("ECU Write failed!");
    }

    /**
     * Function called by the Usb Serial Thread.
     * @param f CAN Frame received from device.
     */
    public void proceedCAN(CANFrame f) {
        if((f.id & 0x7FF) == 0x7A0) rxqueue.offer(f);
    }
}