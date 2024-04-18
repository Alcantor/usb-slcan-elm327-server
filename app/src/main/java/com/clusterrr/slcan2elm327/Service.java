package com.clusterrr.slcan2elm327;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.graphics.BitmapFactory;
import android.content.Intent;
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
    final static String TAG = "SLcan2elm327";
    final static String FORCE_RESTART = "force_restart";
    final static String KEY_ELM_PORT = "elm_port";
    final static String KEY_NET_ENABLED = "net_enabled";
    final static String KEY_NET_PORT = "net_port";

    String localIp;
    UsbSerialThread threadUsb = null;
    ElmServerThread threadElm = null;
    NetServerThread threadNet = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        /* Restart if not properly stopped */
        if(threadUsb != null && threadElm != null){
            if(intent.getBooleanExtra(FORCE_RESTART, true)) this.onDestroy();
            else return START_NOT_STICKY;
        }

        String message = getString(R.string.app_name) + " " + getString(R.string.running);
        Intent mainActivityIntent = new Intent(this, MainActivity.class);
        PendingIntent mainActivityPendingIntent = PendingIntent.getActivity(this, 0, mainActivityIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(TAG,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.app_name));
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, TAG)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_launcher))
                .setContentTitle(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setContentIntent(mainActivityPendingIntent)
                .setSound(null)
                .build();
        startForeground(1, notification);

        localIp = getIPAddress();
        threadUsb = new UsbSerialThread(this);
        threadElm = new ElmServerThread(this, intent.getIntExtra(KEY_ELM_PORT,35000));
        threadNet = new NetServerThread(this, intent.getIntExtra(KEY_NET_PORT,4444));
        threadUsb.start();
        threadElm.start();
        if(intent.getBooleanExtra(KEY_NET_ENABLED,true)) threadNet.start();

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        threadUsb.close();
        threadElm.close();
        threadNet.close();
        stopForeground(true);
    }

    public static String getIPAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isUp()) {
                    String name = networkInterface.getName();
                    if (name.toLowerCase().equals("wlan0") || name.toLowerCase().equals("rmnet0")) {
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
