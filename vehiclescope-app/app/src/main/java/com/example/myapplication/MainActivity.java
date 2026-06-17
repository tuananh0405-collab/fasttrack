package com.example.myapplication;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.datasource.VehicleState;
import com.example.myapplication.service.VehicleDataService;

/**
 * Dashboard activity — binds to VehicleDataService over AIDL and renders
 * live vehicle properties.
 *
 * Car lifecycle note: on AAOS the activity may be paused/resumed as the user
 * switches display areas.  We bind in onStart / unbind in onStop so we only
 * receive updates while visible, and we keep the service running independently
 * (startService) so it survives the activity being stopped.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG              = "VehicleScope/Dashboard";
    private static final long   RECONNECT_BASE   = 2_000L;
    private static final long   RECONNECT_MAX    = 30_000L;

    private TextView tvSpeed, tvFuel, tvGear, tvBattery, tvStatus;
    private IVehicleDataService vehicleService;
    private boolean bound = false;
    private long reconnectDelay = RECONNECT_BASE;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // IVehicleDataCallback — runs on a Binder thread; always post to main thread
    // -------------------------------------------------------------------------

    private final IVehicleDataCallback.Stub callback = new IVehicleDataCallback.Stub() {
        @Override
        public void onSpeedChanged(float mps) {
            float kmh = mps * 3.6f;
            mainHandler.post(() -> tvSpeed.setText(String.format("%.0f km/h", kmh)));
        }
        @Override
        public void onFuelLevelChanged(float fraction) {
            mainHandler.post(() -> tvFuel.setText(String.format("Fuel  %.0f%%", fraction * 100)));
        }
        @Override
        public void onGearChanged(int gear) {
            mainHandler.post(() -> tvGear.setText("Gear  " + gearLabel(gear)));
        }
    };

    // -------------------------------------------------------------------------
    // ServiceConnection — exponential reconnect backoff on disconnect
    // -------------------------------------------------------------------------

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            vehicleService = IVehicleDataService.Stub.asInterface(service);
            bound = true;
            reconnectDelay = RECONNECT_BASE;
            mainHandler.post(() -> tvStatus.setText("Connected"));
            Log.d(TAG, "service connected");

            try {
                // Interface versioning: check version before calling v2 methods
                int ver = vehicleService.getVersion();
                Log.d(TAG, "service version=" + ver);
                vehicleService.registerCallback(callback);

                if (ver >= 2) {
                    float bat = vehicleService.getBatteryLevel();
                    if (bat >= 0) {
                        mainHandler.post(() ->
                                tvBattery.setText(String.format("Battery  %.0f%%", bat * 100)));
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "registerCallback failed", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vehicleService = null;
            bound = false;
            Log.w(TAG, "service disconnected — retry in " + reconnectDelay + " ms");
            mainHandler.post(() -> {
                tvStatus.setText("Disconnected — reconnecting…");
                clearData();
            });
            mainHandler.postDelayed(MainActivity.this::bindToService, reconnectDelay);
            reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX);
        }
    };

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvSpeed   = findViewById(R.id.tv_speed);
        tvFuel    = findViewById(R.id.tv_fuel);
        tvGear    = findViewById(R.id.tv_gear);
        tvBattery = findViewById(R.id.tv_battery);
        tvStatus  = findViewById(R.id.tv_status);

        // Keep service alive independent of activity lifecycle
        startService(new Intent(this, VehicleDataService.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindToService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacksAndMessages(null);
        if (bound) {
            try { vehicleService.unregisterCallback(callback); }
            catch (RemoteException e) { Log.w(TAG, "unregister failed", e); }
            unbindService(serviceConnection);
            bound = false;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void bindToService() {
        bindService(new Intent(this, VehicleDataService.class),
                serviceConnection, BIND_AUTO_CREATE);
    }

    private void clearData() {
        tvSpeed.setText("-- km/h");
        tvFuel.setText("Fuel  --");
        tvGear.setText("Gear  --");
        tvBattery.setText("Battery  --");
    }

    private static String gearLabel(int gear) {
        switch (gear) {
            case VehicleState.GEAR_PARK:    return "P";
            case VehicleState.GEAR_NEUTRAL: return "N";
            case VehicleState.GEAR_REVERSE: return "R";
            case VehicleState.GEAR_DRIVE:   return "D";
            case VehicleState.GEAR_FIRST:   return "1";
            case VehicleState.GEAR_SECOND:  return "2";
            case VehicleState.GEAR_THIRD:   return "3";
            case VehicleState.GEAR_FOURTH:  return "4";
            default:                        return "?";
        }
    }
}