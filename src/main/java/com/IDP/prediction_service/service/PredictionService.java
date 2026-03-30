package com.IDP.prediction_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.IDP.prediction_service.model.AnomalyEvent;
import com.IDP.prediction_service.model.VehicleHistoryEntity;
import com.IDP.prediction_service.model.VehicleLocation;
import com.IDP.prediction_service.repository.VehicleHistoryRepository;
import com.IDP.prediction_service.util.GeoCalculator;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PredictionService {

    private final Map<String, Disposable> pendingPredictions = new ConcurrentHashMap<>();
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final VehicleHistoryRepository postgresRepo;
    private final ObjectMapper objectMapper;

    private static final String PREDICTED_GEO_KEY = "predicted_locations";
    private static final int ASSUMED_PING_INTERVAL_SEC = 5;

    public PredictionService(ReactiveRedisTemplate<String, Object> redisTemplate,
                             VehicleHistoryRepository postgresRepo,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.postgresRepo = postgresRepo;
        this.objectMapper = objectMapper;
    }

    public void processSignalLost(AnomalyEvent event) {
        String sessionId = event.sessionId();

        Disposable timer = Mono.delay(Duration.ofSeconds(10))
                .flatMap(d -> {
                    System.out.println("🔮 [PREDICTING] Calculating ghost path for: " + sessionId);

                    if (event.trail() == null || event.trail().size() < 2) {
                        System.err.println("⚠️ Not enough trail data to predict movement for " + sessionId);
                        return Mono.empty();
                    }

                    // --- CALCULATION LOGIC ---
                    VehicleLocation newest = event.trail().get(0);
                    VehicleLocation older = event.trail().get(1);

                    // 1. Calculate the physics data
                    double distanceMeters = GeoCalculator.calculateDistance(older.latitude(), older.longitude(), newest.latitude(), newest.longitude());
                    double speedMps = distanceMeters / ASSUMED_PING_INTERVAL_SEC;
                    double bearing = GeoCalculator.calculateBearing(older.latitude(), older.longitude(), newest.latitude(), newest.longitude());

                    // 2. Decide the starting point: Anchor for the frontend
                    Point lastKnownPoint = new Point(newest.longitude(), newest.latitude());

                    // 3. Create the enriched member string for Redis (type:sessionId:speed:bearing)
                    String memberValue = event.lastSeen().vehicleType() + ":" + sessionId + ":" + speedMps + ":" + bearing;

                    // --- PERSISTENCE LOGIC ---
                    String trailJson = "[]";
                    try {
                        trailJson = objectMapper.writeValueAsString(event.trail());
                    } catch (Exception e) {
                        System.err.println("Could not parse trail to JSON");
                    }

                    VehicleHistoryEntity historyLog = new VehicleHistoryEntity(
                            sessionId,
                            event.lastSeen().vehicleType(),
                            event.eventType(),
                            event.lastSeen().latitude(),
                            event.lastSeen().longitude(),
                            trailJson
                    );

                    Mono<Long> saveToRedis = redisTemplate.opsForGeo().add(PREDICTED_GEO_KEY, lastKnownPoint, memberValue)
                            .doOnSuccess(res -> System.out.println("📍 Ghost metadata saved to Redis for " + sessionId));

                    Mono<VehicleHistoryEntity> saveToPostgres = postgresRepo.save(historyLog)
                            .doOnSuccess(res -> System.out.println("💾 Signal Lost permanently recorded in Postgres for " + sessionId));

                    return saveToRedis.then(saveToPostgres);
                })
                .doFinally(signalType -> pendingPredictions.remove(sessionId))
                .subscribe(
                        null,
                        err -> System.err.println("❌ Error saving prediction/history: " + err.getMessage())
                );

        pendingPredictions.put(sessionId, timer);
    }

    public void processSignalRestored(String sessionId) {
        Disposable pendingTask = pendingPredictions.remove(sessionId);
        if (pendingTask != null) {
            pendingTask.dispose();
            System.out.println("🛑 [CANCELLED] Signal restored early for " + sessionId + ". Postgres write aborted.");
        }

        redisTemplate.opsForZSet().range(PREDICTED_GEO_KEY, org.springframework.data.domain.Range.unbounded())
                .filter(memberObj -> {
                    String memberStr = String.valueOf(memberObj);
                    String[] parts = memberStr.split(":");
                    return parts.length > 1 && parts[1].equals(sessionId);
                })
                .flatMap(memberObj -> redisTemplate.opsForGeo().remove(PREDICTED_GEO_KEY, memberObj))
                .subscribe(
                        null,
                        err -> System.err.println("❌ Error removing ghost: " + err),
                        () -> System.out.println("🧹 Cleaned up moving ghost vehicle for " + sessionId)
                );
    }
    @Scheduled(fixedRate = 2000)
    public void animateGhostsInBackend() {
        // 1. Get all ghost members currently in the Predicted Geo Set
        redisTemplate.opsForZSet().range(PREDICTED_GEO_KEY, org.springframework.data.domain.Range.unbounded())
                .flatMap(memberObj -> {
                    String memberValue = String.valueOf(memberObj);

                    // 2. Parse the member string (Format: type:sessionId:speed:bearing)
                    String cleanMember = memberValue.replace("\"", "").trim();
                    String[] parts = cleanMember.split(":");

                    // Safety check: Ensure we have at least Type and SessionId
                    if (parts.length < 2) return Mono.empty();
                    String sessionId = parts[1];

                    // 3. STEP 1: Check if the Journey is officially DEAD (Tombstone Check)
                    return redisTemplate.hasKey("journey:ended:" + sessionId)
                            .flatMap(isDead -> {
                                if (Boolean.TRUE.equals(isDead)) {
                                    // If dead, delete the ghost from Redis and stop
                                    return redisTemplate.opsForGeo().remove(PREDICTED_GEO_KEY, memberValue)
                                            .then(Mono.empty());
                                }

                                // 4. STEP 2: If Alive, get current Ghost Position
                                return redisTemplate.opsForGeo().position(PREDICTED_GEO_KEY, memberValue)
                                        .flatMap(pointObj -> {
                                            if (pointObj == null) return Mono.empty();

                                            org.springframework.data.geo.Point currentPoint = (org.springframework.data.geo.Point) pointObj;

                                            // Ensure we have speed and bearing for the math
                                            if (parts.length < 4) return Mono.empty();

                                            try {
                                                double speedMps = Double.parseDouble(parts[2]);
                                                double bearing = Double.parseDouble(parts[3]);

                                                // 5. STEP 3: Calculate the 2-second jump
                                                double distanceTraveled = speedMps * 2.0;

                                                // Using your GeoCalculator utility
                                                org.springframework.data.geo.Point newPoint = GeoCalculator.calculateDestination(
                                                        currentPoint.getY(), // Latitude
                                                        currentPoint.getX(), // Longitude
                                                        bearing,
                                                        distanceTraveled
                                                );

                                                // 6. STEP 4: Update Redis with the new coordinate
                                                return redisTemplate.opsForGeo().add(PREDICTED_GEO_KEY, newPoint, memberValue);

                                            } catch (Exception e) {
                                                // Log parsing errors but don't crash the stream
                                                return Mono.empty();
                                            }
                                        });
                            });
                })
                .subscribe(
                        null, // OnNext: We don't need to do anything with the result
                        err -> System.err.println("❌ Error in Ghost Engine: " + err.getMessage())
                );
    }

    public Mono<Void> startGhostFromHistory(String sessionId) {
        return postgresRepo.findBySessionId(sessionId)
                .flatMap(history -> {
                    try {
                        // 1. Parse the JSON string from the "trail_data" column
                        // Assuming the format is: [{"lat": 22.5, "lng": 88.3}, ...]
                        List<Map<String, Double>> trail = objectMapper.readValue(
                                history.getTrailData(),
                                new TypeReference<List<Map<String, Double>>>() {}
                        );

                        if (trail == null || trail.size() < 2) {
                            return Mono.empty();
                        }

                        // 2. Get the last two points
                        Map<String, Double> latest = trail.get(trail.size() - 1);
                        Map<String, Double> previous = trail.get(trail.size() - 2);

                        // 3. Calculate distance between them
                        double distance = GeoCalculator.calculateDistance(
                                previous.get("lat"), previous.get("lng"),
                                latest.get("lat"), latest.get("lng")
                        );

                        // 4. Determine Speed (Meters per Second)
                        // Assuming a 2-second interval between pings
                        double speedMps = distance / 2.0;

                        // 5. Determine Bearing (Direction)
                        double bearing = GeoCalculator.calculateBearing(
                                previous.get("lat"), previous.get("lng"),
                                latest.get("lat"), latest.get("lng")
                        );

                        // 6. Build the Physics String for Redis
                        String memberValue = String.format("%s:%s:%.2f:%.2f",
                                history.getVehicleType(), sessionId, speedMps, bearing);

                        // 7. Launch the Ghost in Redis
                        org.springframework.data.geo.Point startPoint =
                                new org.springframework.data.geo.Point(latest.get("lng"), latest.get("lat"));

                        return redisTemplate.opsForGeo().add(PREDICTED_GEO_KEY, startPoint, memberValue).then();

                    } catch (Exception e) {
                        System.err.println("❌ Failed to parse trail_data: " + e.getMessage());
                        return Mono.empty();
                    }
                });
    }
    public Mono<Void> cleanUpHistory(String sessionId) {
        return postgresRepo.deleteBySessionId(sessionId)
                .doOnSuccess(v -> System.out.println("🗑️ SQL Cleared for session: " + sessionId))
                .doOnError(e -> System.err.println("❌ SQL Cleanup Failed: " + e.getMessage()));
    }
}