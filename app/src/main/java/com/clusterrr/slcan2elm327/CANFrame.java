package com.clusterrr.slcan2elm327;

public class CANFrame {
    public int id;
    public boolean id_extended;
    public boolean rtr;
    public byte [] data;

    public CANFrame(int id, boolean id_extended, boolean rtr, byte [] data) {
        this.id = id;
        this.id_extended = id_extended;
        this.rtr = rtr;
        this.data = data;
    }

    public static CANFrame fromSLCAN(String s) {
        if(s.charAt(0) == 't') {
            int id = Integer.parseInt(s.substring(1, 4), 16);
            int len = Integer.parseInt(s.substring(4, 5), 16);
            byte [] data = new byte[len];
            for(int i = 0; i < len; ++i){
                int i2 = 5+i*2;
                data[i] = (byte)Integer.parseInt(s.substring(i2, i2+2), 16);
            }
            return new CANFrame(id, false, false, data);
        }
        if(s.charAt(0) == 'T') {
            int id = Integer.parseInt(s.substring(1, 9), 16);
            int len = Integer.parseInt(s.substring(9, 10), 16);
            byte [] data = new byte[len];
            for(int i = 0; i < len; ++i){
                int i2 = 10+i*2;
                data[i] = (byte)Integer.parseInt(s.substring(i2, i2+2), 16);
            }
            return new CANFrame(id, true, false, data);
        }
        if(s.charAt(0) == 'r') {
            int id = Integer.parseInt(s.substring(1, 4), 16);
            int len = Integer.parseInt(s.substring(4, 5), 16);
            byte [] data = new byte[len];
            return new CANFrame(id, false, true, data);
        }
        if(s.charAt(0) == 'R') {
            int id = Integer.parseInt(s.substring(1, 9), 16);
            int len = Integer.parseInt(s.substring(9, 10), 16);
            byte [] data = new byte[len];
            return new CANFrame(id, true, true, data);
        }
        return null;
    }

    public String toSLCAN() {
        StringBuilder s = new StringBuilder(32);
        if (id_extended){
            s.append(String.format("T%08X%1X", id, data.length));
        }else{
            s.append(String.format("t%03X%1X", id, data.length));
        }
        if (rtr) {
            s.setCharAt(0,(id_extended) ? 'R' : 'r');
        } else {
            for (int i = 0; i < data.length; ++i) {
                s.append(String.format("%02X", data[i]));
            }
        }
        s.append('\r');
        return s.toString();
    }

    public static CANFrame fromELM(int id, boolean id_extended, String s) {
        int len = s.length()/2;
        /* TODO: Implement RTR Frame */
        /* Standard ELM327 doesn't support ISO-15765-2 multi frame payloads. */
        if(len <= 7){
            byte [] data = new byte[len+1];
            data[0] = (byte)(len & 0x0F); /* PCI byte */
            for(int i = 0; i < len; ++i){
                int i2 = i*2;
                data[i+1] = (byte)Integer.parseInt(s.substring(i2, i2+2), 16);
            }
            return new CANFrame(id, id_extended, false, data);
        }
        return null;
    }

    public String toELM(boolean header, boolean space, boolean linefeed) {
        StringBuilder s = new StringBuilder(40);
        /* TODO: Implement RTR Frame */
        int i = 0; /* Include PCI byte(s) */
        if (header) {
            if (id_extended) s.append(String.format("%08X", id));
            else s.append(String.format("%03X", id));
            if (space) s.append(' ');
        }else{
            if(iso15765_isSingleFrame()) {
                i = 1; /* Exclude PCI: 1 byte */
            } else if(iso15765_isFirstFrame()) {
                s.append(String.format("%03X\r", (data[0] & 0x0F) << 8 | data[1]));
                if(linefeed) s.append('\n');
                s.append("0:");
                if (space) s.append(' ');
                i = 2; /* Exclude PCI: 2 bytes */
            } else if(iso15765_isConsecutiveFrame()) {
                s.append(String.format("%1X:", data[0] & 0x0F));
                if (space) s.append(' ');
                i = 1; /* Exclude PCI: 1 byte */
            }
        }
        for (; i<data.length; ++i) {
            s.append(String.format("%02X", data[i]));
            if (space) s.append(' ');
        }
        s.append('\r');
        if(linefeed) s.append('\n');
        return s.toString();
    }

    public boolean iso15765_isSingleFrame() {
        return data.length >= 1 && (data[0] >> 4) == 0;
    }
    public boolean iso15765_isFirstFrame() {
        return data.length >= 2 && (data[0] >> 4) == 1;
    }
    public boolean iso15765_isConsecutiveFrame() {
        return data.length >= 1 && (data[0] >> 4) == 2;
    }
    public boolean iso15765_isFlowFrame() {
        return data.length >= 1 && (data[0] >> 4) == 3;
    }
}
