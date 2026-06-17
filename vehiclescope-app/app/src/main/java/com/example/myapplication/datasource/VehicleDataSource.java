package com.example.myapplication.datasource;

public interface VehicleDataSource {
    void start(PropertyListener listener);
    void stop();
    VehicleState getCurrentState();
}
