package com.clusterrr.slcan2elm327.script;

import android.os.Environment;

import com.clusterrr.slcan2elm327.Lotus;
import com.clusterrr.slcan2elm327.R;
import com.clusterrr.slcan2elm327.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScriptRunner implements Runnable{
    private final Service service;
    private final Lotus lt;
    private Map<String, List<Command>> scripts;
    /** Set by the UI thread, cleared by the script thread. */
    private volatile String runningScript;
    private Thread scriptThread;
    private final Pattern patSection = Pattern.compile("^\\[(.*)\\]$");

    public ScriptRunner(Service service, Lotus lt) {
        this.service = service;
        this.lt = lt;
        scripts = null;
        runningScript = null;
        try {
            loadScript();
            service.statusUpdateScr(service.getString(R.string.scr_all_loaded));
        } catch (Exception e) {
            service.statusUpdateScr(service.getString(R.string.scr_error_load) + e.getMessage());
        }
    }

    public String [] getScriptName(){
        if(scripts == null) return new String[] {service.getString(R.string.not_available)};
        return scripts.keySet().toArray(new String[0]);
    }

    public boolean execute(String scriptName){
        if(runningScript != null || !scripts.containsKey(scriptName)) return false;
        runningScript = scriptName;
        /* Kept, rather than fire-and-forget, so close() can stop a script that
         * is halfway through writing to the ECU instead of letting it run on
         * against an adapter the service has already released. */
        scriptThread = new Thread(this, "script-" + scriptName);
        scriptThread.start();
        return true;
    }

    @Override
    public void run() {
        try {
            for(Command c : scripts.get(runningScript)) c.run();
        } catch (InterruptedException e) {
            /* close() asked us to stop; the sleeps in Wait and Upload land here. */
            service.statusUpdateScr(service.getString(R.string.scr_aborted));
        } catch (Exception e) {
            service.statusUpdateScr(service.getString(R.string.scr_error) + e.getMessage());
        }
        runningScript = null;
    }

    /** Stop any running script and wait for it to unwind. */
    public void close() {
        Thread t = scriptThread;
        scriptThread = null;
        if (t == null) return;
        try {
            t.interrupt();
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void switchCommand(String line, List<Command> c) {
        Matcher m;
        m = WriteImm.matchU8(line);
        if (m.matches()) {
            c.add(WriteImm.WriteU8(lt, m));
            return;
        }
        m = WriteImm.matchU16(line);
        if (m.matches()) {
            c.add(WriteImm.WriteU16(lt, m));
            return;
        }
        m = WriteImm.matchU32(line);
        if (m.matches()) {
            c.add(WriteImm.WriteU32(lt, m));
            return;
        }
        m = Download.match(line);
        if (m.matches()) {
            c.add(new Download(lt, m));
            return;
        }
        m = Upload.match(line);
        if (m.matches()) {
            c.add(new Upload(lt, m));
            return;
        }
        m = Verify.match(line);
        if (m.matches()) {
            c.add(new Verify(lt, m));
            return;
        }
        m = Message.match(line);
        if (m.matches()) {
            c.add(new Message(service, m));
            return;
        }
        m = Wait.match(line);
        if (m.matches()) {
            c.add(new Wait(m));
            return;
        }
    }

    public void loadScript() throws IOException {
        File file = new File(Environment.getExternalStorageDirectory(), "lotus-script.ini");
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        scripts = new LinkedHashMap<>();
        List<Command> c = null;
        while ((line = reader.readLine()) != null) {
            Matcher m = patSection.matcher(line);
            if (m.matches()) {
                c = new ArrayList<>();
                scripts.put(m.group(1), c);
            }
            else if(c != null) switchCommand(line, c);
        }
    }
}
