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

import com.hoho.android.usbserial.driver.UsbSerialPort;

public class Service extends android.app.Service {
    final static String TAG = "SLcan2elm327";
    final static String FORCE_RESTART = "force_restart";
    final static String KEY_TCP_PORT = "tcp_port";
    final static String KEY_BAUD_RATE = "baud_rate";
    final static String KEY_DATA_BITS = "data_bits";
    final static String KEY_STOP_BITS = "stop_bits";
    final static String KEY_PARITY = "parity";

    UsbSerialThread threadUSB = null;
    TcpServerThread threadNET = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        /* Restart if not properly stopped */
        if(threadUSB != null && threadNET != null){
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

        try {
            threadUSB = new UsbSerialThread(this,
                    intent.getIntExtra(KEY_BAUD_RATE, 115200),
                    intent.getIntExtra(KEY_DATA_BITS, 8),
                    intent.getIntExtra(KEY_STOP_BITS, UsbSerialPort.STOPBITS_1),
                    intent.getIntExtra(KEY_PARITY, UsbSerialPort.PARITY_NONE));
            threadNET = new TcpServerThread(this, intent.getIntExtra(KEY_TCP_PORT,35000));
            threadUSB.start();
            threadNET.start();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        threadUSB.close();
        threadNET.close();
        stopForeground(true);
    }

    // Communication Service <-> Activity  /////////////////////////////////////////////////////////
    private StatusUpdateCallback statcb;
    private String lastStatusUSB;
    private String lastStatusNET;

    @Override
    public void onCreate(){
        statcb = null;
        lastStatusUSB = getString(R.string.usb_not_started);
        lastStatusNET = getString(R.string.net_not_started);
    }

    public void setMessageCallback(StatusUpdateCallback callback) {
        this.statcb = callback;
    }

    public void statusUpdateUSB(String newStatus) {
        lastStatusUSB = newStatus;
        if (statcb != null) statcb.onStatusUpdateUSB(newStatus);
    }

    public void statusUpdateNET(String newStatus) {
        lastStatusNET = newStatus;
        if (statcb != null) statcb.onStatusUpdateNET(newStatus);
    }

    String getLastStatusUSB() { return lastStatusUSB; }
    String getLastStatusNET() { return lastStatusNET; }

    public interface StatusUpdateCallback
    {
        void onStatusUpdateUSB(String message);
        void onStatusUpdateNET(String message);
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
