package com.clusterrr.slcan2elm327.script;

import android.os.Environment;

import com.clusterrr.slcan2elm327.Lotus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Wait implements Command {
    private static final Pattern pat = Pattern.compile("^wait (\\d+)$");
    private final int time;

    public static Matcher match(String line){
        return pat.matcher(line);
    }

    public Wait(Matcher m){
        time = Integer.parseInt(m.group(1), 10);
    }

    @Override
    public void run() throws Exception {
        Thread.sleep(time);
    }
}
