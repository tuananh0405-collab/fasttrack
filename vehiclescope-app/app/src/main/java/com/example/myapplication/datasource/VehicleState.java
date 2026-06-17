package com.example.myapplication.datasource;

public final class VehicleState {
    // VehicleGear values from VHAL (android.hardware.automotive.vehicle.VehicleGear)
    public static final int GEAR_NEUTRAL = 0x0001;
    public static final int GEAR_REVERSE = 0x0002;
    public static final int GEAR_PARK    = 0x0004;
    public static final int GEAR_DRIVE   = 0x0008;
    public static final int GEAR_FIRST   = 0x0010;
    public static final int GEAR_SECOND  = 0x0020;
    public static final int GEAR_THIRD   = 0x0040;
    public static final int GEAR_FOURTH  = 0x0080;

    private volatile float speedMps       = 0f;
    private volatile float fuelFraction   = 1f;
    private volatile float batteryFraction = -1f; // -1 = unsupported
    private volatile int   gear           = GEAR_PARK;

    public float getSpeedMps()        { return speedMps; }
    public float getFuelFraction()    { return fuelFraction; }
    public float getBatteryFraction() { return batteryFraction; }
    public int   getGear()            { return gear; }

    public void setSpeed(float v)           { speedMps = v; }
    public void setFuelFraction(float v)    { fuelFraction = Math.max(0f, Math.min(1f, v)); }
    public void setBatteryFraction(float v) { batteryFraction = v; }
    public void setGear(int v)              { gear = v; }
}
