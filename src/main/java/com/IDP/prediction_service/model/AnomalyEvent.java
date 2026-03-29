package com.IDP.prediction_service.model;

import java.util.List;

public record AnomalyEvent(
        String eventType,
        String sessionId,
        VehicleLocation lastSeen,
        List<VehicleLocation> trail
) {}