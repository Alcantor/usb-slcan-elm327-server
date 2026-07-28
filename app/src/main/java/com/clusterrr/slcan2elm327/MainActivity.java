package com.clusterrr.slcan2elm327;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.androidcan.CanDeviceFactory;
import com.androidcan.CanDeviceInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, Service.StatusUpdateCallback {
    /* The settings the Service consumes are declared there, so both sides
     * cannot drift apart; autostart is the one only this screen uses. */
    final static String SETTING_CAN_DEVICE = Service.SETTING_CAN_DEVICE;
    final static String SETTING_ELM_PORT = Service.SETTING_ELM_PORT;
    final static String SETTING_NET_MODE = Service.SETTING_NET_MODE;
    final static String SETTING_NET_PORT = Service.SETTING_NET_PORT;
    final static String SETTING_AUTOSTART = "autostart";

    final static int AUTOSTART_DISABLED = 0;
    final static int AUTOSTART_ENABLED = 1;
    final static int AUTOSTART_OBDLINK = 2;

    private Button buttonStart, buttonStop;
    private EditText textElmPort, textNetPort;
    private Spinner spCanDevice, spNetMode, spAutostart;
    /** Serial per spinnerCanDevice row; row 0 is the remembered adapter. */
    private final List<String> canDeviceKeys = new ArrayList<>();
    /** Adapters already asked about, so a refusal does not loop the dialog. */
    private final Set<String> askedForPermission = new HashSet<>();
    private TextView statusUsb, statusElm, statusNet, statusScr;
    private Menu menu = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonStart = findViewById(R.id.buttonStart);
        buttonStop = findViewById(R.id.buttonStop);
        textElmPort = findViewById(R.id.editTextElmPort);
        spNetMode = findViewById(R.id.spinnerNetMode);
        textNetPort = findViewById(R.id.editTextNetPort);
        spCanDevice = findViewById(R.id.spinnerCanDevice);
        spAutostart = findViewById(R.id.spinnerAutostart);

        statusUsb = findViewById(R.id.textViewStatusUsb);
        statusElm = findViewById(R.id.textViewStatusElm);
        statusNet = findViewById(R.id.textViewStatusNet);
        statusScr = findViewById(R.id.textViewStatusScr);

        /* Set default text. */
        statusUsb.setText(getString(R.string.usb_not_started));
        statusElm.setText(getString(R.string.elm_not_started));
        statusNet.setText(getString(R.string.net_not_started));
        statusScr.setText(getString(R.string.scr_not_loaded));

        buttonStart.setOnClickListener(this);
        buttonStop.setOnClickListener(this);

        autostart(this);
        updateSettings(false);
        Intent serviceIntent = new Intent(this, Service.class);
        bindService(serviceIntent, serviceConnection, 0); // in case if service already started
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, usbPermissionReceiver,
                new IntentFilter(UsbCanManager.ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_EXPORTED);
        /* An adapter may have been plugged in while we were away. */
        refreshCanDevices();
        autostart(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(usbPermissionReceiver);
        askedForPermission.clear(); /* Ask again on the next visit. */
    }

    /**
     * Rebuild the adapter picker from what is attached right now, keeping the
     * saved choice selected. The remembered adapter keeps its row even when it
     * is unplugged, so visiting this screen without it does not silently
     * discard the choice.
     */
    private void refreshCanDevices() {
        String saved = Service.prefs(this).getString(SETTING_CAN_DEVICE, "");
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<CanDeviceInfo> found = CanDeviceFactory.enumerate(usbManager);

        /* getSerialNumber() throws without permission, and the serial is the
         * only thing that tells two adapters of the same model apart. So ask
         * first, one dialog at a time - the answer comes back as a broadcast
         * and brings us straight back here. Each adapter is asked about once
         * per visit to this screen: the answer may well be "no", and asking
         * again on the way back would loop the dialog for ever. */
        for (CanDeviceInfo info : found) {
            if (!usbManager.hasPermission(info.device)
                    && askedForPermission.add(info.device.getDeviceName())) {
                usbManager.requestPermission(info.device, UsbCanManager.permissionIntent(this));
                return;
            }
        }

        List<String> labels = new ArrayList<>();
        canDeviceKeys.clear();
        /* Not a first run: offer the remembered adapter, whether or not it is
         * plugged in right now, so coming here without it keeps the choice. */
        if (!saved.isEmpty()) {
            labels.add(getString(R.string.can_device_last));
            canDeviceKeys.add(saved);
        }
        for (CanDeviceInfo info : found) {
            /* Refused above, so the serial is unreadable - and asking for it
             * anyway would throw rather than return null. */
            if (!usbManager.hasPermission(info.device)) continue;
            String serial = info.device.getSerialNumber();
            if (serial == null) continue; // Adapter carries no serial string.
            labels.add(info.displayName() + "  " + serial);
            canDeviceKeys.add(serial);
        }
        if (labels.isEmpty()) {
            labels.add(getString(R.string.can_device_none));
            canDeviceKeys.add("");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCanDevice.setAdapter(adapter);
        spCanDevice.setSelection(Math.max(canDeviceKeys.indexOf(saved), 0));
    }

    /** Brings us back to refreshCanDevices() once a permission dialog is answered. */
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshCanDevices();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(serviceConnection);
    }

    @Override
    public void onClick(View view)
    {
        /* R fields are no longer compile-time constants, so they cannot be
         * switch labels - see android.nonFinalResIds, removed in AGP 9. */
        int id = view.getId();
        if (id == R.id.buttonStart) start();
        else if (id == R.id.buttonStop) stop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.menu = menu;
        if(service == null || service.script == null)
            menu.add(R.string.not_available);
        else
            for (String s : service.script.getScriptName()) menu.add(s);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(service == null || service.script == null)
            return super.onOptionsItemSelected(item);
        return service.script.execute(item.getTitle().toString());
    }

    @Override
    public void onStatusUpdateUsb(final String message) {
        runOnUiThread(() -> statusUsb.setText(message));
    }

    public void onStatusUpdateElm(final String message) {
        runOnUiThread(() -> statusElm.setText(message));
    }

    public void onStatusUpdateNet(final String message) {
        runOnUiThread(() -> statusNet.setText(message));
    }

    public void onStatusUpdateScr(final String message) {
        runOnUiThread(() -> statusScr.setText(message));
    }

    private static Intent startService(Context c, boolean force_restart){
        Intent serviceIntent = new Intent(c, Service.class);
        serviceIntent.putExtra(Service.FORCE_RESTART, force_restart);
        c.startForegroundService(serviceIntent);
        return serviceIntent;
    }

    private void start() {
        saveSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
                return;
            }
        } else {
            // For versions prior to Android 11, guide the user to app settings if WRITE_EXTERNAL_STORAGE permission is not granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
                return;
            }
        }

        Intent ignoreOptimization = prepareIntentForWhiteListingOfBatteryOptimization(
                this, getPackageName(), false);
        if (ignoreOptimization != null) startActivity(ignoreOptimization);

        Intent serviceIntent = startService(this.getBaseContext(), true);
        bindService(serviceIntent, serviceConnection, 0);
        updateSettings(true);
    }

    private void stop() {
        Intent serviceIntent = new Intent(this.getBaseContext(), Service.class);
        stopService(serviceIntent);
        updateSettings(false);
    }

    static void autostart(Context c){
        SharedPreferences prefs = Service.prefs(c);
        int autostart = prefs.getInt(MainActivity.SETTING_AUTOSTART, MainActivity.AUTOSTART_DISABLED);
        if (autostart != MainActivity.AUTOSTART_DISABLED) {
            MainActivity.startService(c, false);
            /* Autostart OBDLink too, but after a delay of 5 seconds. */
            if (autostart == MainActivity.AUTOSTART_OBDLINK) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Intent intent = c.getPackageManager().getLaunchIntentForPackage("OCTech.Mobile.Applications.OBDLink");
                    if (intent != null) c.startActivity(intent);
                }, 5000);
            }
        }
    }

    // Settings ////////////////////////////////////////////////////////////////////////////////////
    private void saveSettings() {
        SharedPreferences prefs = Service.prefs(this);
        int position = spAutostart.getSelectedItemPosition();
        int device = spCanDevice.getSelectedItemPosition();
        prefs.edit()
                .putString(SETTING_CAN_DEVICE,
                        device >= 0 && device < canDeviceKeys.size() ? canDeviceKeys.get(device) : "")
                .putInt(SETTING_ELM_PORT, parsePort(textElmPort, Service.DEFAULT_ELM_PORT))
                .putInt(SETTING_NET_MODE, spNetMode.getSelectedItemPosition())
                .putInt(SETTING_NET_PORT, parsePort(textNetPort, Service.DEFAULT_NET_PORT))
                .putInt(SETTING_AUTOSTART, position)
                .apply();
    }

    private static int parsePort(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void updateSettings(boolean started) {
        SharedPreferences prefs = Service.prefs(this);
        buttonStart.setEnabled(!started);
        buttonStop.setEnabled(started);
        refreshCanDevices();
        spCanDevice.setEnabled(!started);
        textElmPort.setEnabled(!started);
        spNetMode.setEnabled(!started);
        textNetPort.setEnabled(!started);
        spAutostart.setEnabled(!started);
        textElmPort.setText(String.valueOf(prefs.getInt(SETTING_ELM_PORT, Service.DEFAULT_ELM_PORT)));
        spNetMode.setSelection(prefs.getInt(SETTING_NET_MODE, Service.NET_MODE_BOTH));
        textNetPort.setText(String.valueOf(prefs.getInt(SETTING_NET_PORT, Service.DEFAULT_NET_PORT)));
        spAutostart.setSelection(prefs.getInt(SETTING_AUTOSTART, AUTOSTART_DISABLED));
    }

    // Communication Service <-> Activity  /////////////////////////////////////////////////////////
    private Service service = null;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Service.LocalBinder binder = (Service.LocalBinder) iBinder;
            service = binder.getService();
            service.setMessageCallback(MainActivity.this);
            statusUsb.setText(service.getLastStatusUsb());
            statusElm.setText(service.getLastStatusElm());
            statusNet.setText(service.getLastStatusNet());
            statusScr.setText(service.getLastStatusScr());
            updateSettings(true);
            if (menu != null && service.script != null) {
                menu.clear();
                for (String s : service.script.getScriptName()) menu.add(s);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            service = null;
            updateSettings(false);
            /* Set default text. */
            statusUsb.setText(getString(R.string.usb_not_started));
            statusElm.setText(getString(R.string.elm_not_started));
            statusNet.setText(getString(R.string.net_not_started));
            statusScr.setText(getString(R.string.scr_not_loaded));
            if (menu != null) {
                menu.clear();
                menu.add(getString(R.string.not_available));
            }
        }
    };

    // Whitelisting of Battery Optimization  ///////////////////////////////////////////////////////
    private static Intent prepareIntentForWhiteListingOfBatteryOptimization(Context context, String packageName, boolean alsoWhenWhiteListed) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == PackageManager.PERMISSION_DENIED)
            return null;
        final WhiteListedInBatteryOptimizations appIsWhiteListedFromPowerSave = getIfAppIsWhiteListedFromBatteryOptimizations(context, packageName);
        Intent intent = null;
        switch (appIsWhiteListedFromPowerSave) {
            case WHITE_LISTED:
                if (alsoWhenWhiteListed)
                    intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                break;
            case NOT_WHITE_LISTED:
                intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + packageName));
                break;
            case ERROR_GETTING_STATE:
            default:
                break;
        }
        return intent;
    }

    private enum WhiteListedInBatteryOptimizations {
        WHITE_LISTED, NOT_WHITE_LISTED, ERROR_GETTING_STATE
    }

    private static WhiteListedInBatteryOptimizations getIfAppIsWhiteListedFromBatteryOptimizations(Context context, String packageName) {
        final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null)
            return WhiteListedInBatteryOptimizations.ERROR_GETTING_STATE;
        return pm.isIgnoringBatteryOptimizations(packageName) ? WhiteListedInBatteryOptimizations.WHITE_LISTED : WhiteListedInBatteryOptimizations.NOT_WHITE_LISTED;
    }
}
