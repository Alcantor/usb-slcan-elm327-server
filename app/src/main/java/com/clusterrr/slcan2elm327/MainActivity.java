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
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialPort;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, AdapterView.OnItemSelectedListener, Service.StatusUpdateCallback {
    final static String SETTING_TCP_PORT = "tcp_port";
    final static String SETTING_BAUD_RATE = "baud_rate";
    final static String SETTING_DATA_BITS = "data_bits";
    final static String SETTING_STOP_BITS = "stop_bits";
    final static String SETTING_PARITY = "parity";
    final static String SETTING_AUTOSTART = "autostart";

    final static int AUTOSTART_DISABLED = 0;
    final static int AUTOSTART_ENABLED = 1;
    final static int AUTOSTART_OBDLINK = 2;

    private Button buttonStart, buttonStop;
    private EditText textTcpPort, textBaudRate;
    private Spinner spDataBits, spStopBits, spParity, spAutostart;
    private TextView statusUSB, statusNET;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonStart = findViewById(R.id.buttonStart);
        buttonStop = findViewById(R.id.buttonStop);
        textTcpPort = findViewById(R.id.editTextTcpPort);
        textBaudRate = findViewById(R.id.editTextNumberBaudRate);
        spDataBits = findViewById(R.id.spinnerDataBits);
        spStopBits = findViewById(R.id.spinnerStopBits);
        spParity = findViewById(R.id.spinnerParity);
        statusUSB = findViewById(R.id.textViewStatusUSB);
        statusNET = findViewById(R.id.textViewStatusNET);
        spAutostart = findViewById(R.id.spinnerAutostart);

        spAutostart.setOnItemSelectedListener(this);
        buttonStart.setOnClickListener(this);
        buttonStop.setOnClickListener(this);

        statusUSB.setText(getString(R.string.usb_not_started));
        statusNET.setText(getString(R.string.net_not_started));

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
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() != R.id.spinnerAutostart) return;
        SharedPreferences prefs = getSharedPreferences(getString(R.string.app_name), Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(SETTING_AUTOSTART, position)
                .commit();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // Unused
    }

    @Override
    public void onStatusUpdateUSB(String message) { statusUSB.setText(message); }
    public void onStatusUpdateNET(String message) { statusNET.setText(message); }

    public static Intent startService(Context c, boolean force_restart){
        Intent serviceIntent = new Intent(c, Service.class);
        SharedPreferences prefs = c.getSharedPreferences(c.getString(R.string.app_name), Context.MODE_PRIVATE);
        serviceIntent.putExtra(Service.FORCE_RESTART, force_restart);
        serviceIntent.putExtra(Service.KEY_TCP_PORT, prefs.getInt(SETTING_TCP_PORT, 2323));
        serviceIntent.putExtra(Service.KEY_BAUD_RATE, prefs.getInt(SETTING_BAUD_RATE, 115200));
        serviceIntent.putExtra(Service.KEY_DATA_BITS, prefs.getInt(SETTING_DATA_BITS, 3) + 5);
        switch (prefs.getInt(SETTING_STOP_BITS, 0)) {
            case 0:
                serviceIntent.putExtra(Service.KEY_STOP_BITS, UsbSerialPort.STOPBITS_1);
                break;
            case 1:
                serviceIntent.putExtra(Service.KEY_STOP_BITS, UsbSerialPort.STOPBITS_1_5);
                break;
            case 2:
                serviceIntent.putExtra(Service.KEY_STOP_BITS, UsbSerialPort.STOPBITS_2);
                break;
        }
        serviceIntent.putExtra(Service.KEY_PARITY, prefs.getInt(SETTING_PARITY, 0));
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

    public static void autostart(Context c){
        SharedPreferences prefs = c.getSharedPreferences(c.getString(R.string.app_name), Context.MODE_PRIVATE);
        int autostart = prefs.getInt(MainActivity.SETTING_AUTOSTART, MainActivity.AUTOSTART_DISABLED);
        if (autostart!= MainActivity.AUTOSTART_DISABLED) {
            MainActivity.startService(c, false);
            /* Autostart OBDLink too, but after a delay of 5 seconds. */
            if(autostart == MainActivity.AUTOSTART_OBDLINK){
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = c.getPackageManager().getLaunchIntentForPackage("OCTech.Mobile.Applications.OBDLink");
                        if (intent != null) c.startActivity(intent);
                    }
                }, 5000);
            }
        }
    }

    // Settings ////////////////////////////////////////////////////////////////////////////////////
    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.app_name), Context.MODE_PRIVATE);
        int tcpPort;
        try {
            tcpPort = Integer.parseInt(this.textTcpPort.getText().toString());
        }
        catch (NumberFormatException e) {
            tcpPort = 35000;
        }
        int baudRate;
        try {
            baudRate = Integer.parseInt(this.textBaudRate.getText().toString());
        }
        catch (NumberFormatException e) {
            baudRate = 115200;
        }
        prefs.edit()
                .putInt(SETTING_TCP_PORT, tcpPort)
                .putInt(SETTING_BAUD_RATE, baudRate)
                .putInt(SETTING_DATA_BITS, spDataBits.getSelectedItemPosition())
                .putInt(SETTING_STOP_BITS, spStopBits.getSelectedItemPosition())
                .putInt(SETTING_PARITY, spStopBits.getSelectedItemPosition())
                .commit();
    }

    private void updateSettings(boolean started) {
        SharedPreferences prefs = getSharedPreferences(getString(R.string.app_name), Context.MODE_PRIVATE);
        buttonStart.setEnabled(!started);
        buttonStop.setEnabled(started);
        textTcpPort.setEnabled(!started);
        textBaudRate.setEnabled(!started);
        spDataBits.setEnabled(!started);
        spStopBits.setEnabled(!started);
        spParity.setEnabled(!started);
        textTcpPort.setText(String.valueOf(prefs.getInt(SETTING_TCP_PORT, 35000)));
        textBaudRate.setText(String.valueOf(prefs.getInt(SETTING_BAUD_RATE, 115200)));
        spDataBits.setSelection(prefs.getInt(SETTING_DATA_BITS, 3));
        spStopBits.setSelection(prefs.getInt(SETTING_STOP_BITS, 0));
        spParity.setSelection(prefs.getInt(SETTING_PARITY, 0));
        spAutostart.setSelection(prefs.getInt(SETTING_AUTOSTART, AUTOSTART_DISABLED));
    }

    // Communication Service <-> Activity  /////////////////////////////////////////////////////////
    Service service = null;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Service.LocalBinder binder = (Service.LocalBinder) iBinder;
            service = binder.getService();
            service.setMessageCallback(MainActivity.this);
            statusUSB.setText(service.getLastStatusUSB());
            statusNET.setText(service.getLastStatusNET());
            updateSettings(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            service = null;
            updateSettings(false);
        }
    };

    // Whitelisting of Battery Optimization  ///////////////////////////////////////////////////////
    public static Intent prepareIntentForWhiteListingOfBatteryOptimization(Context context, String packageName, boolean alsoWhenWhiteListed) {
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

    public enum WhiteListedInBatteryOptimizations {
        WHITE_LISTED, NOT_WHITE_LISTED, ERROR_GETTING_STATE, UNKNOWN_TOO_OLD_ANDROID_API_FOR_CHECKING, IRRELEVANT_OLD_ANDROID_API
    }

    public static WhiteListedInBatteryOptimizations getIfAppIsWhiteListedFromBatteryOptimizations(Context context, String packageName) {
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
