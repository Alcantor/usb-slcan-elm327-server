package com.clusterrr.slcan2elm327;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.androidcan.CanDevice;
import com.androidcan.CanDeviceFactory;
import com.androidcan.CanDeviceInfo;
import com.androidcan.CanFrame;
import com.androidcan.FrameListener;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the USB CAN adapter: waits for a supported one to appear, obtains USB
 * permission, opens it through the androidCAN library and goes on-bus.
 *
 * <p>Consumers of the bus register with {@link #addFrameListener} and are
 * handed frames by the library's own dispatch, which catches whatever a
 * listener throws so one misbehaving consumer cannot starve the others or kill
 * the RX loop. This class only re-attaches them whenever a new adapter is
 * opened, since each attach produces a fresh {@link CanDevice}.</p>
 *
 * <p>There is no thread of our own here. Reading the bus is already the
 * library's RX thread, and attach/detach/permission all arrive as broadcasts,
 * so the only thing left needing a background context is {@code open()} /
 * {@code stop()} / {@code close()} - synchronous USB control transfers that
 * must not run on the main thread a receiver is called on. A single-thread
 * executor covers that and serializes them at the same time, so an unplug
 * can never race an open.</p>
 */
public class UsbCanManager {
    /** The bus runs at 500 kbit/s, as did the old SLCAN "S6" setup. */
    public final static int BITRATE = 500000;

    final static String ACTION_USB_PERMISSION =
            "com.clusterrr.slcan2elm327.USB_PERMISSION";

    private final Service service;
    private final UsbManager usbManager;
    /** Serial of the adapter to use; empty on a first run, then remembered. */
    private volatile String deviceSerial;
    private final ExecutorService worker;
    private final CopyOnWriteArrayList<FrameListener> listeners;
    private volatile CanDevice device;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice affected = usbDeviceOf(intent);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                /* Only react to our own adapter going away; then look for
                 * another one, in case a second adapter is still attached. */
                CanDevice dev = device;
                if (affected != null && dev != null && affected.equals(dev.getUsbDevice())) {
                    worker.execute(() -> {
                        closeDevice();
                        open();
                    });
                }
            } else {
                /* Attach, or the answer to a permission request. Either way the
                 * response is the same: try again. open() re-checks the grant
                 * itself, so a spoofed broadcast cannot open anything. */
                worker.execute(UsbCanManager.this::open);
            }
        }
    };

    public UsbCanManager(Service service, String deviceSerial) {
        this.service = service;
        this.usbManager = (UsbManager) service.getSystemService(Context.USB_SERVICE);
        this.deviceSerial = deviceSerial;
        this.worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "usb-can"));
        this.listeners = new CopyOnWriteArrayList<>();
        this.device = null;
    }

    /**
     * The adapter a USB broadcast is about. The untyped
     * {@code getParcelableExtra} is deprecated but remains the only option
     * below API 33, so the split is suppressed here rather than at the call
     * site. (androidx's {@code IntentCompat} would hide it, but that needs
     * androidx.core 1.10+; appcompat 1.6.1 resolves core to 1.9.0.)
     */
    @SuppressWarnings("deprecation")
    private static UsbDevice usbDeviceOf(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    /** Register a consumer of the bus. Call before {@link #start}. */
    public void addFrameListener(FrameListener listener) {
        listeners.add(listener);
    }

    public void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        /* Attach and detach are system broadcasts, so this has to be exported. */
        ContextCompat.registerReceiver(service, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        /* An adapter may well be plugged in already - no broadcast for that. */
        worker.execute(this::open);
    }

    /**
     * Where the answer to a USB permission dialog is delivered. Built the same
     * way from the service and from the settings screen on purpose: a
     * PendingIntent's identity is its package, request code and intent, not the
     * context that created it, so both get the one broadcast and both react.
     */
    static PendingIntent permissionIntent(Context context) {
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    /** Open the chosen supported adapter, if it is attached and we may use it. */
    private void open() {
        if (device != null) return; // Already connected.

        UsbDevice chosen = null;
        String serial = null;
        for (CanDeviceInfo candidate : CanDeviceFactory.enumerate(usbManager)) {
            /* Permission first: getSerialNumber() throws SecurityException
             * without it once the app targets Android Q or later, and reading
             * the serial is the only way to tell one adapter from another. */
            if (!usbManager.hasPermission(candidate.device)) {
                service.statusUpdateUsb(service.getString(R.string.usb_permission));
                usbManager.requestPermission(candidate.device, permissionIntent(service));
                return; // The answer comes back as a broadcast.
            }
            serial = candidate.device.getSerialNumber();
            /* An empty preference means whichever adapter turns up first. */
            if (deviceSerial.isEmpty() || deviceSerial.equals(serial)) {
                chosen = candidate.device;
                break;
            }
        }

        /* Everything attached is identified, and none of it is ours. */
        if (chosen == null) {
            service.statusUpdateUsb(service.getString(R.string.usb_wait));
            return;
        }

        CanDevice dev = CanDeviceFactory.create(usbManager, chosen);
        if (dev == null) return; // Enumerated but unsupported, cannot happen.
        try {
            dev.open();
            for (FrameListener listener : listeners) dev.addFrameListener(listener);
            dev.start(BITRATE);
        } catch (Exception e) {
            e.printStackTrace();
            service.statusUpdateUsb(service.getString(R.string.usb_error));
            for (FrameListener listener : listeners) dev.removeFrameListener(listener);
            dev.close();
            return;
        }
        device = dev;
        /* This is the moment "last used adapter" is established - on a first
         * run there was nothing to match against and we took the first one
         * found. Kept in the field too, so an unplug and replug in this same
         * session comes back to the same adapter rather than to first-found.
         * An adapter with no serial string cannot be remembered at all. */
        if (serial != null) {
            deviceSerial = serial;
            Service.prefs(service).edit()
                    .putString(Service.SETTING_CAN_DEVICE, serial).apply();
        }
        service.statusUpdateUsb(service.getString(R.string.usb_connected)
                + (serial == null ? "?" : serial));
    }

    /** Go off-bus and release the adapter. Only ever called on {@link #worker}. */
    private void closeDevice() {
        CanDevice dev = device;
        device = null;
        if (dev == null) return;
        for (FrameListener listener : listeners) dev.removeFrameListener(listener);
        try {
            dev.stop();
        } catch (IOException e) {
            e.printStackTrace(); // Expected if the adapter was unplugged.
        }
        dev.close();
    }

    public void sendCAN(CanFrame f) {
        CanDevice dev = device;
        if (dev == null) return;
        try {
            dev.send(f);
        } catch (IOException e) {
            service.statusUpdateUsb(service.getString(R.string.usb_error));
        }
    }

    public void close() {
        service.statusUpdateUsb(service.getString(R.string.usb_stopping));
        try {
            service.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            // start() was never called.
        }
        worker.execute(this::closeDevice);
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        service.statusUpdateUsb(service.getString(R.string.usb_stopped));
    }
}
