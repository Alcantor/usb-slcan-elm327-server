package com.clusterrr.slcan2elm327;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

public class Service extends android.app.Service {
    final static String TAG = "can2elm327";
    /** The only intent extra: everything else is read from the settings. */
    final static String FORCE_RESTART = "force_restart";

    /* Settings this service reads. MainActivity writes them; keeping the keys
     * in one place is what stops the two sides drifting apart. */
    final static String SETTING_ELM_PORT = "elm_port";
    final static String SETTING_CAN_DEVICE = "can_device";
    final static String SETTING_NET_MODE = "net_mode";
    final static String SETTING_NET_PORT = "net_port";

    /* Which way frames may flow over the cannelloni link, named from the
     * client's end: TO_BUS is a sensor node that only ever sends, FROM_BUS a
     * monitor that only ever listens. Values are the spinner's row order. */
    final static int NET_MODE_DISABLED = 0;
    final static int NET_MODE_BOTH = 1;
    final static int NET_MODE_TO_BUS = 2;
    final static int NET_MODE_FROM_BUS = 3;

    final static int DEFAULT_ELM_PORT = 35000;
    /** cannelloni's own default port. */
    final static int DEFAULT_NET_PORT = 20000;

    /** The one shared preferences file, so its name is spelled out once. */
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(c.getString(R.string.app_name), Context.MODE_PRIVATE);
    }

    String localIp;
    UsbCanManager usbCan = null;
    ElmServer elm = null;
    CannelloniServer net = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        /* Restart if not properly stopped. A null intent means START_STICKY
         * brought us back after the process was killed, which always wants a
         * clean start - and is the only thing we ask the intent at all. */
        if(usbCan != null && elm != null){
            if(intent == null || intent.getBooleanExtra(FORCE_RESTART, true)) this.onDestroy();
            else return START_NOT_STICKY;
        }

        String message = getString(R.string.app_name) + " " + getString(R.string.running);
        Intent mainActivityIntent = new Intent(this, MainActivity.class);
        PendingIntent mainActivityPendingIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(TAG,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(getString(R.string.app_name));
        nm.createNotificationChannel(channel);
        Notification notification = new NotificationCompat.Builder(this, TAG)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon())
                .setContentTitle(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setContentIntent(mainActivityPendingIntent)
                .setSound(null)
                .build();
        startForeground(1, notification);

        /* Read straight from the settings rather than from intent extras, so a
         * START_STICKY restart - which arrives with no extras at all - comes
         * back on exactly the configuration the user chose. MainActivity saves
         * them before asking us to start, which this relies on. */
        SharedPreferences prefs = prefs(this);

        localIp = getIPAddress();
        usbCan = new UsbCanManager(this, prefs.getString(SETTING_CAN_DEVICE, ""));
        elm = new ElmServer(this, prefs.getInt(SETTING_ELM_PORT, DEFAULT_ELM_PORT));
        int netMode = prefs.getInt(SETTING_NET_MODE, NET_MODE_BOTH);
        net = new CannelloniServer(this,
                prefs.getInt(SETTING_NET_PORT, DEFAULT_NET_PORT), netMode);
        /* Everything that consumes the bus registers itself; the androidCAN
         * driver dispatches to each of them from its RX thread. */
        usbCan.addFrameListener(elm);
        usbCan.addFrameListener(net);
        usbCan.start();
        elm.start();
        if(netMode != NET_MODE_DISABLED) net.start();

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        if(usbCan != null) usbCan.close();
        if(elm != null) elm.close();
        if(net != null) net.close();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    /* The launcher icon is an <adaptive-icon> XML, and BitmapFactory only
     * decodes image files - it returns null for anything compiled from XML.
     * Rasterise the drawable instead, at the size the shade expects. */
    private Bitmap largeIcon() {
        Drawable icon = getDrawable(R.mipmap.ic_launcher);
        if (icon == null) return null;
        int size = getResources().getDimensionPixelSize(
                android.R.dimen.notification_large_icon_width);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(bitmap));
        return bitmap;
    }

    public static String getIPAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isUp()) {
                    String name = networkInterface.getName();
                    if (name.equalsIgnoreCase("wlan0") || name.equalsIgnoreCase("rmnet0")) {
                        List<InterfaceAddress> interfaceAddresses = networkInterface.getInterfaceAddresses();
                        for (InterfaceAddress interfaceAddress : interfaceAddresses) {
                            InetAddress address = interfaceAddress.getAddress();
                            if (address instanceof Inet4Address){
                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    // Communication Service <-> Activity  /////////////////////////////////////////////////////////
    private StatusUpdateCallback statcb;
    private String lastStatusUsb;
    private String lastStatusElm;
    private String lastStatusNet;

    @Override
    public void onCreate(){
        statcb = null;
        lastStatusUsb = getString(R.string.usb_not_started);
        lastStatusElm = getString(R.string.elm_not_started);
        lastStatusNet = getString(R.string.net_not_started);
    }

    public void setMessageCallback(StatusUpdateCallback callback) {
        this.statcb = callback;
    }

    public void statusUpdateUsb(String newStatus) {
        if (lastStatusUsb != newStatus) {
            lastStatusUsb = newStatus;
            if (statcb != null) statcb.onStatusUpdateUsb(newStatus);
        }
    }

    public void statusUpdateElm(String newStatus) {
        if (lastStatusElm != newStatus) {
            lastStatusElm = newStatus;
            if (statcb != null) statcb.onStatusUpdateElm(newStatus);
        }
    }

    public void statusUpdateNet(String newStatus) {
        if (lastStatusNet != newStatus) {
            lastStatusNet = newStatus;
            if (statcb != null) statcb.onStatusUpdateNet(newStatus);
        }
    }

    String getLastStatusUsb() { return lastStatusUsb; }
    String getLastStatusElm() { return lastStatusElm; }
    String getLastStatusNet() { return lastStatusNet; }

    public interface StatusUpdateCallback
    {
        void onStatusUpdateUsb(String message);
        void onStatusUpdateElm(String message);
        void onStatusUpdateNet(String message);
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        statcb = null;
        return false;
    }

    public class LocalBinder extends Binder {
        Service getService() {
            return Service.this;
        }
    }
}
