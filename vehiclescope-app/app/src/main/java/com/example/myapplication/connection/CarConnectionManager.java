package com.example.myapplication.connection;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.example.myapplication.datasource.CarApiDataSource;
import com.example.myapplication.datasource.FakeVhalDataSource;
import com.example.myapplication.datasource.PropertyListener;
import com.example.myapplication.datasource.VehicleDataSource;

/**
 * Manages the connection to android.car.Car and drives the Car lifecycle.
 *
 * Lifecycle contract:
 *   connect() → onCarReady(source) when CarService is up (or fake mode active)
 *   CarService goes away → onCarUnavailable()
 *   CarService comes back → onCarReady(source) again
 *
 * Fake-VHAL mode activates automatically when Car API is not available
 * (e.g. running on a phone or an emulator without AAOS system image).
 * Force it on any device with:  adb shell setprop vehiclescope.fake_vhal true
 */
public class CarConnectionManager {

    private static final String TAG            = "VehicleScope/CarConn";
    private static final String PROP_FAKE_VHAL = "vehiclescope.fake_vhal";

    public interface ConnectionListener {
        void onCarReady(VehicleDataSource source);
        void onCarUnavailable();
    }

    private final Context            appContext;
    private final ConnectionListener listener;

    private android.car.Car car;
    private HandlerThread   carThread;
    private Handler         carHandler;
    private VehicleDataSource activeSource;

    public CarConnectionManager(Context ctx, ConnectionListener l) {
        appContext = ctx.getApplicationContext();
        listener   = l;
    }

    public void connect(PropertyListener propListener) {
        if (isFakeModeRequested()) {
            Log.d(TAG, "fake_vhal=true → FakeVhalDataSource");
            startFake(propListener);
            return;
        }

        carThread = new HandlerThread("CarServiceThread");
        carThread.start();
        carHandler = new Handler(carThread.getLooper());

        try {
            car = android.car.Car.createCar(
                    appContext,
                    carHandler,
                    android.car.Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (activeCar, ready) -> {
                        if (ready) {
                            Log.d(TAG, "CarService ready");
                            android.car.hardware.property.CarPropertyManager cpm =
                                    (android.car.hardware.property.CarPropertyManager)
                                            activeCar.getCarManager(android.car.Car.PROPERTY_SERVICE);
                            if (cpm == null) {
                                Log.w(TAG, "CarPropertyManager null — falling back");
                                startFake(propListener);
                                return;
                            }
                            activeSource = new CarApiDataSource(cpm);
                            activeSource.start(propListener);
                            listener.onCarReady(activeSource);
                        } else {
                            Log.w(TAG, "CarService not ready");
                            stopActiveSource();
                            listener.onCarUnavailable();
                        }
                    });

            if (car == null) {
                Log.w(TAG, "Car.createCar returned null — falling back");
                startFake(propListener);
            }
        } catch (NoClassDefFoundError | Exception e) {
            // android.car classes are absent on non-AAOS devices
            Log.w(TAG, "android.car unavailable (" + e.getClass().getSimpleName()
                    + ") — falling back to FakeVhal");
            startFake(propListener);
        }
    }

    public void disconnect() {
        stopActiveSource();
        if (car != null) { car.disconnect(); car = null; }
        if (carThread != null) { carThread.quitSafely(); carThread = null; }
    }

    private void startFake(PropertyListener propListener) {
        activeSource = new FakeVhalDataSource();
        activeSource.start(propListener);
        listener.onCarReady(activeSource);
    }

    private void stopActiveSource() {
        if (activeSource != null) { activeSource.stop(); activeSource = null; }
    }

    private boolean isFakeModeRequested() {
        try {
            // android.os.SystemProperties is a hidden API accessible in privileged/system apps
            String val = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, PROP_FAKE_VHAL, "false");
            return "true".equalsIgnoreCase(val);
        } catch (Exception e) {
            return false;
        }
    }
}
