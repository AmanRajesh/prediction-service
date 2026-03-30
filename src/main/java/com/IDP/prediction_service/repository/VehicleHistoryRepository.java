package com.IDP.prediction_service.repository;

import com.IDP.prediction_service.model.VehicleHistoryEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface VehicleHistoryRepository extends ReactiveCrudRepository<VehicleHistoryEntity, Long> {
    @Query("SELECT * FROM vehicle_history WHERE session_id = :sessionId ORDER BY id DESC LIMIT 1")
    Mono<VehicleHistoryEntity> findBySessionId(String sessionId);



    Mono<Void> deleteBySessionId(String sessionId);
}