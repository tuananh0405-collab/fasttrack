package com.example.myapplication.datasource;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Scripted vehicle scenario for use on the AAOS emulator when real VHAL
 * properties are not accessible.  Activated when CarService is unavailable
 * or when the system property vehiclescope.fake_vhal=true is set.
 *
 * Scenario:
 *   0–30 s  : speed ramps from 0 → 120 km/h (33.33 m/s)
 *   ongoing : speed holds at 120 km/h, fuel slowly drains
 *   gear    : shifts P→1→2→3→4 at speed thresholds
 */
public class FakeVhalDataSource implements VehicleDataSource {

    private static final String TAG = "VehicleScope/FakeVhal";

    // 60 ticks × 500 ms = 30 s to reach MAX_SPEED_MPS
    private static final int    TICK_MS         = 500;
    private static final float  MAX_SPEED_MPS   = 33.33f;
    private static final float  SPEED_INCREMENT = MAX_SPEED_MPS / 60f;
    private static final float  FUEL_DRAIN      = 0.0005f; // per tick

    private HandlerThread thread;
    private Handler       handler;
    private PropertyListener listener;
    private volatile boolean running = false;

    private final VehicleState state = new VehicleState();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void start(PropertyListener l) {
        this.listener = l;
        thread = new HandlerThread("FakeVhal");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        handler.post(tick);
        Log.d(TAG, "started");
    }

    @Override
    public void stop() {
        running = false;
        if (handler != null) handler.removeCallbacks(tick);
        if (thread != null) { thread.quitSafely(); thread = null; }
        Log.d(TAG, "stopped");
    }

    @Override
    public VehicleState getCurrentState() { return state; }

    private void step() {
        // Speed ramp
        float speed = state.getSpeedMps();
        if (speed < MAX_SPEED_MPS) {
            speed = Math.min(speed + SPEED_INCREMENT, MAX_SPEED_MPS);
            state.setSpeed(speed);
            listener.onSpeedChanged(speed);
        }

        // Fuel drain
        float fuel = state.getFuelFraction() - FUEL_DRAIN;
        state.setFuelFraction(fuel);
        listener.onFuelLevelChanged(state.getFuelFraction());

        // Gear shift on speed thresholds
        int newGear = gearForSpeed(speed);
        if (newGear != state.getGear()) {
            state.setGear(newGear);
            listener.onGearChanged(newGear);
            Log.d(TAG, "gear → " + newGear + " at " + String.format("%.1f", speed * 3.6f) + " km/h");
        }
    }

    private static int gearForSpeed(float mps) {
        if (mps < 0.5f)  return VehicleState.GEAR_PARK;
        if (mps < 5f)    return VehicleState.GEAR_FIRST;
        if (mps < 11f)   return VehicleState.GEAR_SECOND;
        if (mps < 18f)   return VehicleState.GEAR_THIRD;
        return VehicleState.GEAR_FOURTH;
    }
}
