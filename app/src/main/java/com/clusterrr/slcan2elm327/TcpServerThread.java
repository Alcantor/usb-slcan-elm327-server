package com.clusterrr.slcan2elm327;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TcpServerThread extends Thread {
    private Service service;
    private ServerSocket serverSock;
    private Socket sock;
    private InputStream dataInputStream;
    private OutputStream dataOutputStream;
    private byte[] buffer;
    private int woff, roff;
    private boolean echo, space, header, linefeed;
    private int timeout, sendid, recvid, recvmask;
    Pattern patAT, patST, patData;
    public BlockingQueue<CANFrame> rxqueue;
    private boolean running;

    public TcpServerThread(Service service, int port) throws IOException {
        this.service = service;
        serverSock = new ServerSocket(port);
        sock = null;
        buffer = new byte[1024];
        woff = roff = 0;
        reset();
        patAT = Pattern.compile("^AT([@A-Z]+)([0-9A-F]*)$");
        patST = Pattern.compile("^ST([@A-Z]+)([0-9A-F]*)$");
        patData = Pattern.compile("^(([0-9A-F][0-9A-F])*)(\\d?)$");
        rxqueue = new ArrayBlockingQueue<>(8);
        running = true;
    }

    @Override
    public void run() {
        try {
            while (running) {
                service.statusUpdateNET(service.getString(R.string.net_wait));
                sock = serverSock.accept();
                service.statusUpdateNET(service.getString(R.string.net_connected) + sock.getRemoteSocketAddress());
                sock.setTcpNoDelay(true);
                dataInputStream = sock.getInputStream();
                dataOutputStream = sock.getOutputStream();
                try {
                    /* Parse data */
                    while (running) {
                        if (dataInputStream == null) break;
                        int l = dataInputStream.read(buffer, woff, buffer.length-woff);
                        if (l <= 0) break; // disconnect
                        if(echo) dataOutputStream.write(buffer, woff, l);
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

    public void write(String s) throws IOException {
        dataOutputStream.write(s.getBytes());
    }

    private void reset(){
        echo = true;
        space = true;
        header = false;
        linefeed = false;
        timeout = 100;
        sendid = 0x7E0;
        recvid = 0x7E8;
        recvmask = 0x7F8;
    }

    private void writePrompt(String s) throws IOException {
        if (linefeed) write(s + "\r\n>");
        else write(s + "\r>");
    }

    private void writeOK() throws IOException {
        writePrompt("OK");
    }

    private void writeNOK() throws IOException {
        writePrompt("?");
    }

    private void proceedBuffer() throws IOException, InterruptedException {
        StringBuilder command = new StringBuilder(64);

        for (int i = roff; i < woff; ++i) {
            char b = (char) buffer[i];
            /* Ignore space and newline */
            if (b == ' ' || b == '\n') continue;
            if (b == '\r') {
                service.statusUpdateNET( service.getString(R.string.net_command) + command);
                Matcher matcherAT = patAT.matcher(command);
                Matcher matcherST = patST.matcher(command);
                Matcher matcherData = patData.matcher(command);
                if (matcherAT.matches()) {
                    String at = matcherAT.group(1);
                    int parameter = 0;
                    if(matcherAT.group(2).length() > 0) {
                        parameter = Integer.parseInt(matcherAT.group(2), 16);
                    }
                    switch (at) {
                        case "@":
                            if (parameter == 1) writePrompt("OBD SOLUTIONS LLC");
                            else writeNOK();
                            break;
                        case "Z":
                            reset();
                            // no break
                        case "I":
                            writePrompt("ELM327 v1.4b");
                            break;
                        case "E":
                            echo = (parameter > 0);
                            writeOK();
                            break;
                        case "S":
                            space = (parameter > 0);
                            writeOK();
                            break;
                        case "H":
                            header = (parameter > 0);
                            writeOK();
                            break;
                        case "L":
                            linefeed = (parameter > 0);
                            writeOK();
                            break;
                        case "RV":
                            writePrompt("12.0V");
                            break;
                        case "SP":
                            /* Only CAN-BUS */
                            if (parameter == 0 || parameter == 6) writeOK();
                            else writeNOK();
                            break;
                        case "DP":
                            writePrompt("AUTO,ISO 15765-4 (CAN11/500)"); /* Only CAN-BUS */
                            break;
                        case "DPN":
                            writePrompt("A6"); /* Only CAN-BUS */
                            break;
                        case "ST":
                            timeout = parameter*4;
                            writeOK();
                            break;
                        case "SH":
                            sendid = parameter;
                            writeOK();
                            break;
                        case "CF":
                            recvid = parameter;
                            writeOK();
                            break;
                        case "CM":
                            recvmask = parameter;
                            writeOK();
                            break;
                        default:
                            writeNOK();
                            break;
                    }
                }
                else if (matcherST.matches()) {
                    String st = matcherST.group(1);
                    switch(st){
                        case "I":
                            writePrompt("STN1155 v5.6.19");
                            break;
                        case "MFR":
                            writePrompt("OBD Solutions LLC");
                            break;
                        case "DI":
                            writePrompt("OBDLink LX BT r2.1.1");
                            break;
                        case "SN":
                            writePrompt("115599999999");
                            break;
                        case "DIX":
                            writePrompt("Device:       OBDLink LX BT r2.1.1\r" +
                                    "Firmware:     STN1155 v5.6.19 [2021.06.29]\r" +
                                    "Mfr:          OBD Solutions LLC\r" +
                                    "Serial #:     115599999999\r" +
                                    "BL Ver:       2.17\r" +
                                    "IC ID/Rev:    0x0100, 0x067F, 0x3004\r" +
                                    "BT Modem:     BT24H, R15, 200915E_Scantool, 000439999999, 001F00\r" +
                                    "BT Dev Name:  OBDLink LX\r" +
                                    "Init Date:    2021.07.16\r" +
                                    "POR Count:    166\r" +
                                    "POR Time:     0 days 00:43:53\r" +
                                    "Tot Run Time: 157 days 18:33\r" +
                                    "Eng Cranks:   254\r" +
                                    "Eng Starts:   245");
                            break;
                        case "UIL":
                        case "BTPM":
                            writeOK();
                            break;
                        default:
                            writeNOK();
                            break;
                    }

                }
                else if (matcherData.matches()) {
                    String data = matcherData.group(1);
                    int max = 16;
                    if(matcherData.group(3).length() > 0) {
                        max = Integer.parseInt(matcherData.group(3), 16);
                    }
                    int len = data.length() / 2;
                    String slcan = String.format("t%03X%d%02X%s\r", sendid, len + 1, len, data);
                    rxqueue.clear();
                    service.threadUSB.write(slcan);
                    int count = 0;
                    while (count < max) {
                        CANFrame f = rxqueue.poll(timeout, TimeUnit.MILLISECONDS);
                        if (f == null) break;
                        write(f.toELM(header, space, linefeed));
                        ++count;
                        /* If it's a single frame exit immediately */
                        if(f.iso15765_isSingleFrame()) break;
                    }
                    if (count > 0) write(">");
                    else writePrompt("NO DATA");
                }
                /* Remove the used data and continue */
                command.setLength(0);
                roff = i + 1;
                if (woff == roff) woff = roff = 0;
            } else {
                command.append(Character.toUpperCase(b));
            }
        }
    }

    /**
     * Function called by the Usb Serial Thread.
     * @param f
     */
    public void proceedCAN(CANFrame f){
        /* No need for AtomicInteger here on recvmask and recvid, the rxqueue will be clear. */
        if((f.id & recvmask) == recvid) rxqueue.offer(f);
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
            service.statusUpdateNET( service.getString(R.string.net_stopping));
            running = false;
            closeClient();
            serverSock.close();
            join();
            service.statusUpdateNET(service.getString(R.string.net_stopped));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}