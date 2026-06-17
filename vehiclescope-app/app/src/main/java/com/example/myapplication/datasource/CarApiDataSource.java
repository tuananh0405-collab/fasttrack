package com.example.myapplication.datasource;

import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.util.Log;

import java.util.List;

/**
 * Live data source backed by CarPropertyManager.
 * Subscribes only to properties reported as supported by the platform
 * (never assumes a property exists — VHAL varies by OEM).
 */
public class CarApiDataSource implements VehicleDataSource {

    private static final String TAG        = "VehicleScope/CarApi";
    private static final float  UPDATE_HZ  = 2f;

    // FUEL_LEVEL comes from VHAL in millilitres; normalise against a 50 L tank
    private static final float  FUEL_TANK_ML = 50_000f;

    private final CarPropertyManager cpm;
    private final VehicleState state = new VehicleState();
    private CarPropertyManager.CarPropertyEventCallback callback;

    public CarApiDataSource(CarPropertyManager cpm) {
        this.cpm = cpm;
    }

    @Override
    public void start(PropertyListener listener) {
        callback = new CarPropertyManager.CarPropertyEventCallback() {
            @Override
            public void onChangeEvent(CarPropertyValue value) {
                dispatch(value, listener);
            }
            @Override
            public void onErrorEvent(int propertyId, int areaId) {
                Log.e(TAG, "error propertyId=0x" + Integer.toHexString(propertyId)
                        + " areaId=" + areaId);
                listener.onError(propertyId, areaId);
            }
        };

        // Guard: only subscribe to properties the platform actually supports.
        // getSupportedPropertyIds() is AAOS API ≥ 29; fall back to getPropertyList().
        List<CarPropertyConfig> supported = cpm.getPropertyList();
        for (CarPropertyConfig<?> cfg : supported) {
            int pid = cfg.getPropertyId();
            if (pid == VehiclePropertyIds.PERF_VEHICLE_SPEED
                    || pid == VehiclePropertyIds.FUEL_LEVEL
                    || pid == VehiclePropertyIds.GEAR_SELECTION
                    || pid == VehiclePropertyIds.EV_BATTERY_LEVEL) {
                boolean ok = cpm.registerCallback(callback, pid, UPDATE_HZ);
                Log.d(TAG, "subscribe 0x" + Integer.toHexString(pid) + " ok=" + ok);
            }
        }
    }

    @Override
    public void stop() {
        if (callback != null) {
            cpm.unregisterCallback(callback);
            callback = null;
        }
    }

    @Override
    public VehicleState getCurrentState() { return state; }

    @SuppressWarnings("unchecked")
    private void dispatch(CarPropertyValue<?> v, PropertyListener l) {
        if (v.getStatus() != CarPropertyValue.STATUS_AVAILABLE) {
            Log.w(TAG, "property 0x" + Integer.toHexString(v.getPropertyId())
                    + " status=" + v.getStatus() + " — skipping");
            return;
        }
        switch (v.getPropertyId()) {
            case VehiclePropertyIds.PERF_VEHICLE_SPEED: {
                float speed = (Float) v.getValue();
                state.setSpeed(speed);
                l.onSpeedChanged(speed);
                break;
            }
            case VehiclePropertyIds.FUEL_LEVEL: {
                float ml = (Float) v.getValue();
                float fraction = Math.min(1f, ml / FUEL_TANK_ML);
                state.setFuelFraction(fraction);
                l.onFuelLevelChanged(fraction);
                break;
            }
            case VehiclePropertyIds.GEAR_SELECTION: {
                int gear = (Integer) v.getValue();
                state.setGear(gear);
                l.onGearChanged(gear);
                break;
            }
            case VehiclePropertyIds.EV_BATTERY_LEVEL: {
                float pct = (Float) v.getValue();
                float fraction = Math.min(1f, pct / 100f);
                state.setBatteryFraction(fraction);
                l.onBatteryLevelChanged(fraction);
                break;
            }
        }
    }
}
