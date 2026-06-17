package com.example.myapplication.datasource;

public interface PropertyListener {
    void onSpeedChanged(float speedMps);
    void onFuelLevelChanged(float fraction);   // 0.0 – 1.0
    void onGearChanged(int gear);              // VehicleGear constants
    void onBatteryLevelChanged(float fraction);
    void onError(int propertyId, int areaId);
}
