package com.clusterrr.slcan2elm327.script;

import com.clusterrr.slcan2elm327.R;
import com.clusterrr.slcan2elm327.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Message implements Command {
    private static final Pattern pat = Pattern.compile("^message \"(.*)\"$");
    Service service;
    private final String message;

    public static Matcher match(String line){
        return pat.matcher(line);
    }

    public Message(Service service, Matcher m){
        this.service = service;
        message = m.group(1);
    }

    @Override
    public void run() throws Exception {
        service.statusUpdateScr(service.getString(R.string.scr_msg) + message);
    }
}
