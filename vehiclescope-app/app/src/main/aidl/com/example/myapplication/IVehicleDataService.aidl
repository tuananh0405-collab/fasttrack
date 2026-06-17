// IVehicleDataService.aidl
package com.example.myapplication;

import com.example.myapplication.IVehicleDataCallback;

interface IVehicleDataService {
    // Always add getVersion() first — clients call this before any v2+ method
    // to guard against AbstractMethodError when bound to an older server.
    int getVersion();

    void registerCallback(IVehicleDataCallback cb);
    void unregisterCallback(IVehicleDataCallback cb);

    float getCurrentSpeed();   // m/s, returns 0 if no data yet

    // v2: added after initial release — always guard with getVersion() >= 2
    float getBatteryLevel();   // 0.0 – 1.0; -1 if property unsupported
}
