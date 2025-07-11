package com.clusterrr.slcan2elm327;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(Service.TAG, "Receive Broadcast BootCompleted");
        MainActivity.autostart(context.getApplicationContext());
    }
}