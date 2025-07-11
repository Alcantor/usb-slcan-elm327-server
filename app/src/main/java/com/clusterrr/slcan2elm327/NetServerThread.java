package com.clusterrr.slcan2elm327;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class NetServerThread extends Thread {
    private final Service service;
    private final int port;
    private ServerSocket serverSock;
    private Socket sock;
    private InputStream dataInputStream;
    private OutputStream dataOutputStream;
    private final byte[] buffer;
    private int woff, roff;
    private boolean running;
    private enum Mode {RX_ONLY, TX_ONLY, NORMAL}
    private Mode mode;

    public NetServerThread(Service service, int port) {
        this.service = service;
        this.port = port;
        sock = null;
        buffer = new byte[1024];
        woff = roff = 0;
        running = true;
        mode = Mode.TX_ONLY;
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
                if(command.charAt(0)  == 'O') mode = Mode.NORMAL;
                else if(command.charAt(0)  == 'C') mode = Mode.TX_ONLY;
                else if(command.charAt(0)  == 'L') mode = Mode.RX_ONLY;
                else{
                    CANFrame f = CANFrame.fromSLCAN(command);
                    if(f != null && (mode == Mode.NORMAL || mode == Mode.TX_ONLY)){
                        service.statusUpdateNet(service.getString(R.string.net_transmit));
                        service.threadUsb.sendCAN(f);
                    }
                }
                /* Remove the used data and continue */
                roff = i + 1;
                if (woff == roff) woff = roff = 0;
            }
        }
    }

    /**
     * Function called by the Usb Serial Thread.
     * @param f CAN Frame received from device.
     */
    public void proceedCAN(CANFrame f) {
        if(sock != null && (mode == Mode.NORMAL || mode == Mode.RX_ONLY)) {
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
            woff = roff = 0;
            mode = Mode.TX_ONLY;
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