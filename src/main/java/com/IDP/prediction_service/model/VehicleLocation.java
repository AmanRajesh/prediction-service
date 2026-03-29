package com.IDP.prediction_service.model;

public record VehicleLocation(
        String sessionId,
        String vehicleType,
        double latitude,
        double longitude
) {}