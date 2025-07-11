package com.clusterrr.slcan2elm327;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, Service.StatusUpdateCallback {
    final static String SETTING_ELM_PORT = "elm_port";
    final static String SETTING_NET_ENABLED = "net_enabled";
    final static String SETTING_NET_PORT = "net_port";
    final static String SETTING_AUTOSTART = "autostart";

    final static int AUTOSTART_DISABLED = 0;
    final static int AUTOSTART_ENABLED = 1;
    final static int AUTOSTART_OBDLINK = 2;

    private Button buttonStart, buttonStop;
    private EditText textElmPort, textNetPort;
    private Switch swNetEnabled;
    private Spinner spAutostart;
    private TextView statusUsb, statusElm, statusNet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonStart = findViewById(R.id.buttonStart);
        buttonStop = findViewById(R.id.buttonStop);
        textElmPort = findViewById(R.id.editTextElmPort);
        swNetEnabled = findViewById(R.id.switchNetEnabled);
        textNetPort = findViewById(R.id.editTextNetPort);
        spAutostart = findViewById(R.id.spinnerAutostart);

        statusUsb = findViewById(R.id.textViewStatusUsb);
        statusElm = findViewById(R.id.textViewStatusElm);
        statusNet = findViewById(R.id.textViewStatusNet);

        /* Set default text. */
        statusUsb.setText(getString(R.string.usb_not_started));
        statusElm.setText(getString(R.string.elm_not_started));
        statusNet.setText(getString(R.string.net_not_started));

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
        autostart(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(serviceConnection);
    }

    @Override
    public void onClick(View view)
    {
        switch(view.getId())
        {
            case R.id.buttonStart:
                start();
                break;
            case R.id.buttonStop:
                stop();
                break;
        }
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

    private static Intent startService(Context c, boolean force_restart){
        Intent serviceIntent = new Intent(c, Service.class);
        SharedPreferences prefs = c.getSharedPreferences(c.getString(R.string.app_name), Context.MODE_PRIVATE);
        serviceIntent.putExtra(Service.FORCE_RESTART, force_restart);
        serviceIntent.putExtra(Service.KEY_ELM_PORT, prefs.getInt(SETTING_ELM_PORT, 35000));
        serviceIntent.putExtra(Service.KEY_NET_ENABLED, prefs.getBoolean(SETTING_NET_ENABLED, true));
        serviceIntent.putExtra(Service.KEY_NET_PORT, prefs.getInt(SETTING_NET_PORT, 4444));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(serviceIntent);
        } else {
            c.startService(serviceIntent);
        }
        return serviceIntent;
    }

    private void start() {
        saveSettings();

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
        SharedPreferences prefs = c.getSharedPreferences(c.getString(R.string.app_name), Context.MODE_PRIVATE);
        int autostart = prefs.getInt(MainActivity.SETTING_AUTOSTART, MainActivity.AUTOSTART_DISABLED);
        if (autostart != MainActivity.AUTOSTART_DISABLED) {
            MainActivity.startService(c, false);
            /* Autostart OBDLink too, but after a delay of 5 seconds. */
            if (autostart == MainActivity.AUTOSTART_OBDLINK) {
                new Handler().postDelayed(() -> {
                    Intent intent = c.getPackageManager().getLaunchIntentForPackage("OCTech.Mobile.Applications.OBDLink");
                    if (intent != null) c.startActivity(intent);
                }, 5000);
            }
        }
    }

    // Settings ////////////////////////////////////////////////////////////////////////////////////
    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.app_name), Context.MODE_PRIVATE);
        int elmPort;
        try {
            elmPort = Integer.parseInt(this.textElmPort.getText().toString());
        }
        catch (NumberFormatException e) {
            elmPort = 35000;
        }
        int netPort;
        try {
            netPort = Integer.parseInt(this.textNetPort.getText().toString());
        }
        catch (NumberFormatException e) {
            netPort = 4444;
        }
        int position = spAutostart.getSelectedItemPosition();
        prefs.edit()
                .putInt(SETTING_ELM_PORT, elmPort)
                .putBoolean(SETTING_NET_ENABLED, swNetEnabled.isChecked())
                .putInt(SETTING_NET_PORT, netPort)
                .putInt(SETTING_AUTOSTART, position)
                .apply();
    }

    private void updateSettings(boolean started) {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.app_name), Context.MODE_PRIVATE);
        buttonStart.setEnabled(!started);
        buttonStop.setEnabled(started);
        textElmPort.setEnabled(!started);
        swNetEnabled.setEnabled(!started);
        textNetPort.setEnabled(!started);
        spAutostart.setEnabled(!started);
        textElmPort.setText(String.valueOf(prefs.getInt(SETTING_ELM_PORT, 35000)));
        swNetEnabled.setChecked(prefs.getBoolean(SETTING_NET_ENABLED, true));
        textNetPort.setText(String.valueOf(prefs.getInt(SETTING_NET_PORT, 4444)));
        spAutostart.setSelection(prefs.getInt(SETTING_AUTOSTART, AUTOSTART_DISABLED));
    }

    // Communication Service <-> Activity  /////////////////////////////////////////////////////////
    private Service service = null;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Service.LocalBinder binder = (Service.LocalBinder) iBinder;
            service = binder.getService();
            service.setMessageCallback(MainActivity.this);
            statusUsb.setText(service.getLastStatusUsb());
            statusElm.setText(service.getLastStatusElm());
            statusNet.setText(service.getLastStatusNet());
            updateSettings(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            service = null;
            updateSettings(false);
            /* Set default text. */
            statusUsb.setText(getString(R.string.usb_not_started));
            statusElm.setText(getString(R.string.elm_not_started));
            statusNet.setText(getString(R.string.net_not_started));
        }
    };

    // Whitelisting of Battery Optimization  ///////////////////////////////////////////////////////
    private static Intent prepareIntentForWhiteListingOfBatteryOptimization(Context context, String packageName, boolean alsoWhenWhiteListed) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return null;
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
            case UNKNOWN_TOO_OLD_ANDROID_API_FOR_CHECKING:
            case IRRELEVANT_OLD_ANDROID_API:
            default:
                break;
        }
        return intent;
    }

    private enum WhiteListedInBatteryOptimizations {
        WHITE_LISTED, NOT_WHITE_LISTED, ERROR_GETTING_STATE, UNKNOWN_TOO_OLD_ANDROID_API_FOR_CHECKING, IRRELEVANT_OLD_ANDROID_API
    }

    private static WhiteListedInBatteryOptimizations getIfAppIsWhiteListedFromBatteryOptimizations(Context context, String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return WhiteListedInBatteryOptimizations.IRRELEVANT_OLD_ANDROID_API;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return WhiteListedInBatteryOptimizations.UNKNOWN_TOO_OLD_ANDROID_API_FOR_CHECKING;
        final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null)
            return WhiteListedInBatteryOptimizations.ERROR_GETTING_STATE;
        return pm.isIgnoringBatteryOptimizations(packageName) ? WhiteListedInBatteryOptimizations.WHITE_LISTED : WhiteListedInBatteryOptimizations.NOT_WHITE_LISTED;
    }
}
