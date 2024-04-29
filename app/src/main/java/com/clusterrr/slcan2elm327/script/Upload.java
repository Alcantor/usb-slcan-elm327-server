package com.clusterrr.slcan2elm327.script;

import android.os.Environment;

import com.clusterrr.slcan2elm327.Lotus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Upload implements Command {
    private static final Pattern pat = Pattern.compile("^upload 0x([0-9a-fA-F]+) \"(.*)\" 0x([0-9a-fA-F]+) 0x([0-9a-fA-F]+)$");
    private final Lotus lt;
    private final int address;
    private final File file;
    private final int offset;
    private final int size;

    public static Matcher match(String line){
        return pat.matcher(line);
    }

    public Upload(Lotus lt, Matcher m){
        this.lt = lt;
        address = Integer.parseInt(m.group(1), 16);
        file = new File(Environment.getExternalStorageDirectory(), m.group(2));
        offset = Integer.parseInt(m.group(3), 16);
        size = Integer.parseInt(m.group(4), 16);
    }

    @Override
    public void run() throws Exception {
        int i = address, r = size;
        FileInputStream fis = new FileInputStream(file);
        try {
            fis.skip(offset);
            while(r > 0) {
                int chunk_size = Math.min(r, 64);
                byte[] chunk = new byte[chunk_size];
                if(fis.read(chunk) != chunk_size)
                    throw new IOException("Upload not enough file bytes!");
                lt.writeMemory(i, chunk, false);
                Thread.sleep(10); /* Otherwise SLCAN will be overflowed */
                i += chunk_size;
                r -= chunk_size;
            }
        } finally {
            fis.close();
        }
    }
}
