package com.IDP.prediction_service.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.IDP.prediction_service.model.AnomalyEvent;
import com.IDP.prediction_service.service.PredictionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AnomalyEventListener {

    private final PredictionService predictionService;
    private final ObjectMapper objectMapper;

    public AnomalyEventListener(PredictionService predictionService, ObjectMapper objectMapper) {
        this.predictionService = predictionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "prediction-events", groupId = "prediction-group")
    public void onMessage(String messagePayload) {
        try {
            AnomalyEvent event = objectMapper.readValue(messagePayload, AnomalyEvent.class);

            if ("SIGNAL_LOST".equals(event.eventType())) {
                predictionService.processSignalLost(event);
            } else if ("SIGNAL_RESTORED".equals(event.eventType())) {
                predictionService.processSignalRestored(event.sessionId());
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to parse Kafka message: " + e.getMessage());
        }
    }
}