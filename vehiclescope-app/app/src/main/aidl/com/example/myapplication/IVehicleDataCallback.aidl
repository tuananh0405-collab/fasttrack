// IVehicleDataCallback.aidl
package com.example.myapplication;

// oneway: the server must never block on a client callback.
// Callbacks run on a Binder thread in the client process; all UI updates
// must be posted to the main thread on the receiving end.
oneway interface IVehicleDataCallback {
    void onSpeedChanged(float speedMps);
    void onFuelLevelChanged(float fraction);  // 0.0 – 1.0
    void onGearChanged(int gear);             // VehicleGear constants
}
