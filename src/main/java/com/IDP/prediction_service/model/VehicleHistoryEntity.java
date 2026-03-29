package com.IDP.prediction_service.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("vehicle_history")
public class VehicleHistoryEntity {

    @Id
    private Long id;
    private String sessionId;
    private String vehicleType;
    private String eventType;
    private Double lastLatitude;
    private Double lastLongitude;
    private String trailData;
    private LocalDateTime createdAt;

    // 🚨 IMPORTANT: Default constructor for Spring Data
    public VehicleHistoryEntity() {}

    public VehicleHistoryEntity(String sessionId, String vehicleType, String eventType,
                                Double lastLatitude, Double lastLongitude, String trailData) {
        this.sessionId = sessionId;
        this.vehicleType = vehicleType;
        this.eventType = eventType;
        this.lastLatitude = lastLatitude;
        this.lastLongitude = lastLongitude;
        this.trailData = trailData;
        this.createdAt = LocalDateTime.now();
    }

    // Standard Getters (R2DBC needs these to read values for the INSERT statement)
    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getVehicleType() { return vehicleType; }
    public String getEventType() { return eventType; }
    public Double getLastLatitude() { return lastLatitude; }
    public Double getLastLongitude() { return lastLongitude; }
    public String getTrailData() { return trailData; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
}