package com.IDP.prediction_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.IDP.prediction_service.model.AnomalyEvent;
import com.IDP.prediction_service.model.VehicleHistoryEntity;
import com.IDP.prediction_service.model.VehicleLocation;
import com.IDP.prediction_service.repository.VehicleHistoryRepository;
import com.IDP.prediction_service.util.GeoCalculator;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
}