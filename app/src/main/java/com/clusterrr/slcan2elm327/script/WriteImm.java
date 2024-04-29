package com.clusterrr.slcan2elm327.script;

import com.clusterrr.slcan2elm327.Lotus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WriteImm implements Command {
    private static final Pattern patU8 = Pattern.compile("^write_u8 0x([0-9a-fA-F]+) 0x([0-9a-fA-F]+)$");
    private static final Pattern patU16 = Pattern.compile("^write_u16 0x([0-9a-fA-F]+) 0x([0-9a-fA-F]+)$");
    private static final Pattern patU32 = Pattern.compile("^write_u32 0x([0-9a-fA-F]+) 0x([0-9a-fA-F]+)$");
    private final Lotus lt;
    private final int address;
    private final byte [] data;

    public static Matcher matchU8(String line){
        return patU8.matcher(line);
    }
    public static Matcher matchU16(String line){
        return patU16.matcher(line);
    }
    public static Matcher matchU32(String line){
        return patU32.matcher(line);
    }

    private WriteImm(Lotus lt, int address, byte[] data){
        this.lt = lt;
        this.address = address;
        this.data = data;
    }

    public static WriteImm WriteU8(Lotus lt, Matcher m){
        int value = Integer.parseInt(m.group(2), 16);
        return new WriteImm(lt,  Integer.parseInt(m.group(1), 16),
                new byte[] {(byte) (value & 0xFF)});
    }

    public static WriteImm WriteU16(Lotus lt, Matcher m){
        int value = Integer.parseInt(m.group(2), 16);
        return new WriteImm(lt,  Integer.parseInt(m.group(1), 16),
                new byte[] {
                        (byte) ((value >> 8) & 0xFF),
                        (byte) (value & 0xFF)
        });
    }

    public static WriteImm WriteU32(Lotus lt, Matcher m){
        int value = Integer.parseInt(m.group(2), 16);
        return new WriteImm(lt,  Integer.parseInt(m.group(1), 16),
                new byte[] {
                        (byte) ((value >> 24) & 0xFF),
                        (byte) ((value >> 16) & 0xFF),
                        (byte) ((value >> 8) & 0xFF),
                        (byte) (value & 0xFF)
        });
    }

    @Override
    public void run() throws Exception {
        lt.writeMemory(address, data, false);
    }
}
