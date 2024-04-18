package com.clusterrr.slcan2elm327;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class NetServerThread extends Thread {
    private Service service;
    private int port;
    private ServerSocket serverSock;
    private Socket sock;
    private InputStream dataInputStream;
    private OutputStream dataOutputStream;
    private byte[] buffer;
    private int woff, roff;
    private boolean running;

    public NetServerThread(Service service, int port) {
        this.service = service;
        this.port = port;
        sock = null;
        buffer = new byte[1024];
        woff = roff = 0;
        running = true;
    }

    @Override
    public void run() {
        try {
            serverSock = new ServerSocket(port);
            while (running) {
                service.statusUpdateNet(service.getString(R.string.net_wait) + service.localIp);
                sock = serverSock.accept();
                service.statusUpdateNet(service.getString(R.string.net_connected) + sock.getRemoteSocketAddress());
                sock.setTcpNoDelay(true);
                dataInputStream = sock.getInputStream();
                dataOutputStream = sock.getOutputStream();
                try {
                    /* Parse data */
                    while (running) {
                        if (dataInputStream == null) break;
                        int l = dataInputStream.read(buffer, woff, buffer.length-woff);
                        if (l <= 0) break; // disconnect
                        woff += l;
                        proceedBuffer();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                closeClient();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void proceedBuffer() {
        for (int i = roff; i < woff; ++i) {
            if ((char) buffer[i] == '\r') {
                String command = new String(buffer, roff, i-roff, StandardCharsets.ISO_8859_1);
                CANFrame f = CANFrame.fromSLCAN(command);
                if(f != null){
                    service.statusUpdateNet(service.getString(R.string.net_transmit));
                    service.threadUsb.sendCAN(f);
                }
                /* Remove the used data and continue */
                roff = i + 1;
                if (woff == roff) woff = roff = 0;
            }
        }
    }

    /**
     * Function called by the Usb Serial Thread.
     * @param f
     */
    public void proceedCAN(CANFrame f) {
        if(sock != null) {
            String command = f.toSLCAN();
            service.statusUpdateNet(service.getString(R.string.net_transmit));
            try {
                dataOutputStream.write(command.getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                service.statusUpdateNet(service.getString(R.string.net_error));
            }
        }
    }

    private void closeClient() throws IOException {
        if(sock != null){
            dataOutputStream.close();
            dataInputStream.close();
            sock.close();
            sock = null;
        }
    }

    public void close() {
        try {
            service.statusUpdateNet(service.getString(R.string.net_stopping));
            running = false;
            closeClient();
            if(serverSock != null) serverSock.close();
            join();
            service.statusUpdateNet(service.getString(R.string.net_stopped));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}