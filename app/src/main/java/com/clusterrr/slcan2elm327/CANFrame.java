package com.clusterrr.slcan2elm327;

public class CANFrame{
    public int id;
    public boolean id_extended;
    public boolean rtr;
    public byte [] data;

    public CANFrame(int id, boolean id_extended, boolean rtr, byte [] data){
        this.id = id;
        this.id_extended = id_extended;
        this.rtr = rtr;
        this.data = data;
    }

    public static CANFrame fromSLCAN(StringBuilder s){
        if(s.charAt(0) == 't'){
            int id = Integer.parseInt(s.substring(1, 4), 16);
            int len = Integer.parseInt(s.substring(4, 5), 16);
            byte [] data = new byte[len];
            for(int i = 0; i < len; ++i){
                int i2 = 5+i*2;
                data[i] = (byte)Integer.parseInt(s.substring(i2, i2+2), 16);
            }
            return new CANFrame(id, false, false, data);
        }
        if(s.charAt(0) == 'T'){
            int id = Integer.parseInt(s.substring(1, 9), 16);
            int len = Integer.parseInt(s.substring(9, 10), 16);
            byte [] data = new byte[len];
            for(int i = 0; i < len; ++i){
                int i2 = 10+i*2;
                data[i] = (byte)Integer.parseInt(s.substring(i2, i2+2), 16);
            }
            return new CANFrame(id, true, false, data);
        }
        if(s.charAt(0) == 'r'){
            int id = Integer.parseInt(s.substring(1, 4), 16);
            int len = Integer.parseInt(s.substring(4, 5), 16);
            byte [] data = new byte[len];
            return new CANFrame(id, false, true, data);
        }
        if(s.charAt(0) == 'R'){
            int id = Integer.parseInt(s.substring(1, 9), 16);
            int len = Integer.parseInt(s.substring(9, 10), 16);
            byte [] data = new byte[len];
            return new CANFrame(id, true, true, data);
        }
        return null;
    }

    public String toELM(boolean header, boolean space, boolean linefeed){
        StringBuilder s = new StringBuilder(40);
        /* TODO: Handle ISO-15765-2 First and consecutive frame with format 1: 7E8 AA BB */
        /* TODO: RTR Frame ? */
        int i = 1;
        if(header) {
            if (id_extended) s.append(String.format("%08X", id));
            else s.append(String.format("%03X", id));
            if (space) s.append(' ');
            i = 0; /* Include PCI byte */
        }
        for(; i<data.length; ++i){
            s.append(String.format("%02X", data[i]));
            if (space) s.append(' ');
        }
        s.append('\r');
        if(linefeed) s.append('\n');
        return s.toString();
    }

    public boolean iso15765_isSingleFrame(){
        return (data[0] & 0xF0) == 0;
    }
}
