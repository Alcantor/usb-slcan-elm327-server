package com.clusterrr.slcan2elm327.script;

import android.os.Environment;

import com.clusterrr.slcan2elm327.Lotus;

import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Download implements Command {
    private static final Pattern pat = Pattern.compile("^download 0x([0-9a-fA-F]+) \"(.*)\" 0x([0-9a-fA-F]+) (true|false)$");
    private final Lotus lt;
    private final int address;
    private final File file;
    private final int size;
    private final boolean append;

    public static Matcher match(String line){
        return pat.matcher(line);
    }

    public Download(Lotus lt, Matcher m){
        this.lt = lt;
        address = Integer.parseInt(m.group(1), 16);
        file = new File(Environment.getExternalStorageDirectory(), m.group(2));
        size = Integer.parseInt(m.group(3), 16);
        append = Boolean.parseBoolean(m.group(4));
    }

    @Override
    public void run() throws Exception {
        int i = address, r = size;
        FileOutputStream fos = new FileOutputStream(file, append);
        try {
            while (r > 0) {
                int chunk_size = Math.min(r, 64);
                byte[] chunk = lt.readMemory(i, chunk_size);
                fos.write(chunk);
                i += chunk_size;
                r -= chunk_size;
            }
        } finally {
            fos.close();
        }
    }
}
