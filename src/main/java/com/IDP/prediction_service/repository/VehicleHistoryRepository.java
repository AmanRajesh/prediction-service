package com.IDP.prediction_service.repository;

import com.IDP.prediction_service.model.VehicleHistoryEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleHistoryRepository extends ReactiveCrudRepository<VehicleHistoryEntity, Long> {
}