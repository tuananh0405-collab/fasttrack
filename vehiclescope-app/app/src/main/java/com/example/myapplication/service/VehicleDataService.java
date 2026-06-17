package com.example.myapplication.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.example.myapplication.IVehicleDataCallback;
import com.example.myapplication.IVehicleDataService;
import com.example.myapplication.connection.CarConnectionManager;
import com.example.myapplication.datasource.PropertyListener;
import com.example.myapplication.datasource.VehicleDataSource;
import com.example.myapplication.datasource.VehicleState;

public class VehicleDataService extends Service {

    private static final String TAG        = "VehicleScope/Service";
    private static final String CHANNEL_ID = "vehicle_data";
    private static final int    NOTIF_ID   = 1001;

    // Increment when adding new AIDL methods; clients call getVersion() before
    // invoking any method added after v1 to avoid AbstractMethodError.
    static final int INTERFACE_VERSION = 2;

    private final RemoteCallbackList<IVehicleDataCallback> callbacks =
            new RemoteCallbackList<>();

    private CarConnectionManager connectionManager;
    private volatile VehicleState currentState = new VehicleState();

    // -------------------------------------------------------------------------
    // PropertyListener — receives events from whichever VehicleDataSource is active
    // -------------------------------------------------------------------------

    private final PropertyListener propertyListener = new PropertyListener() {
        @Override public void onSpeedChanged(float mps)        { broadcastSpeed(mps); }
        @Override public void onFuelLevelChanged(float f)      { broadcastFuel(f); }
        @Override public void onGearChanged(int gear)          { broadcastGear(gear); }
        @Override public void onBatteryLevelChanged(float f)   { broadcastBattery(f); }
        @Override public void onError(int propId, int areaId) {
            Log.e(TAG, "vehicle error prop=0x" + Integer.toHexString(propId)
                    + " area=" + areaId);
        }
    };

    // -------------------------------------------------------------------------
    // AIDL Stub
    // -------------------------------------------------------------------------

    private final IVehicleDataService.Stub binder = new IVehicleDataService.Stub() {

        @Override
        public int getVersion() { return INTERFACE_VERSION; }

        @Override
        public void registerCallback(IVehicleDataCallback cb) {
            if (cb == null) return;
            // Link DeathRecipient BEFORE registering — avoids a window where the
            // binder is in the list but the death recipient is not yet linked.
            try {
                cb.asBinder().linkToDeath(
                        () -> {
                            Log.w(TAG, "client binder died — removing callback");
                            callbacks.unregister(cb);
                        }, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "client already dead at register time");
                return;
            }
            callbacks.register(cb);
            Log.d(TAG, "callback registered  active=" + callbacks.getRegisteredCallbackCount());
        }

        @Override
        public void unregisterCallback(IVehicleDataCallback cb) {
            if (cb == null) return;
            callbacks.unregister(cb);
            Log.d(TAG, "callback unregistered");
        }

        @Override
        public float getCurrentSpeed() {
            return currentState.getSpeedMps();
        }

        @Override
        public float getBatteryLevel() {
            return currentState.getBatteryFraction(); // -1 if unsupported
        }
    };

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }

        connectionManager = new CarConnectionManager(this,
                new CarConnectionManager.ConnectionListener() {
                    @Override
                    public void onCarReady(VehicleDataSource src) {
                        currentState = src.getCurrentState();
                        Log.d(TAG, "data source ready: " + src.getClass().getSimpleName());
                    }
                    @Override
                    public void onCarUnavailable() {
                        Log.w(TAG, "car unavailable");
                    }
                });
        connectionManager.connect(propertyListener);
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        connectionManager.disconnect();
        callbacks.kill();
    }

    // -------------------------------------------------------------------------
    // Broadcast helpers — RemoteCallbackList handles dead-binder cleanup
    // automatically; beginBroadcast/finishBroadcast MUST be called as a pair.
    // -------------------------------------------------------------------------

    private void broadcastSpeed(float mps) {
        int n = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try { callbacks.getBroadcastItem(i).onSpeedChanged(mps); }
                catch (RemoteException e) { Log.w(TAG, "speed broadcast[" + i + "] failed"); }
            }
        } finally { callbacks.finishBroadcast(); }
    }

    private void broadcastFuel(float f) {
        int n = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try { callbacks.getBroadcastItem(i).onFuelLevelChanged(f); }
                catch (RemoteException e) { Log.w(TAG, "fuel broadcast[" + i + "] failed"); }
            }
        } finally { callbacks.finishBroadcast(); }
    }

    private void broadcastGear(int gear) {
        int n = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try { callbacks.getBroadcastItem(i).onGearChanged(gear); }
                catch (RemoteException e) { Log.w(TAG, "gear broadcast[" + i + "] failed"); }
            }
        } finally { callbacks.finishBroadcast(); }
    }

    private void broadcastBattery(float f) {
        // Battery is a v2 addition; only clients that confirmed getVersion() >= 2 use it.
        // The RemoteCallbackList oneway dispatch is still safe for v1 clients — they
        // simply never call getBatteryLevel(), so no AbstractMethodError occurs.
        int n = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                // No explicit v2 callback method in IVehicleDataCallback (kept minimal);
                // battery is exposed via the service's getBatteryLevel() poll method.
            }
        } finally { callbacks.finishBroadcast(); }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Vehicle Data", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Live vehicle telemetry from VehicleScope");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("VehicleScope")
                .setContentText("Monitoring vehicle data")
                .setOngoing(true)
                .build();
    }
}
