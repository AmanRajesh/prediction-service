package com.IDP.prediction_service.consumer;

import com.IDP.prediction_service.service.PredictionService;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LocationEventListener {

    private final PredictionService predictionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    public LocationEventListener(PredictionService predictionService,com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.predictionService = predictionService;
        this.objectMapper = objectMapper;
    }

//    @KafkaListener(topics = "prediction-events", groupId = "prediction-service-group")
//    public void onSignalLost(String sessionId) {
//        System.out.println("📩 Kafka: Signal lost for " + sessionId);
//
//        // Just trigger the service and subscribe
//        predictionService.startGhostFromHistory(sessionId)
//                .subscribe(
//                        null,
//                        err -> System.err.println("❌ Service Error: " + err.getMessage()),
//                        () -> System.out.println("✅ Service: Ghost initialized for " + sessionId)
//                );
//    }

    @KafkaListener(topics = "journey-events", groupId = "prediction-service-group")
    public void onJourneyEnded(String message) { // Rename to 'message'
        try {
            // Parse the incoming JSON string

            com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(message);
            String exactSessionId = json.get("sessionId").asText();

            // 3. Just end it by session ID! Pass the clean string to SQL.
            predictionService.cleanUpHistory(exactSessionId).subscribe();

            System.out.println("🗑️ Successfully wiped SQL for session: " + exactSessionId);
        } catch (Exception e) {
            System.err.println("❌ Error parsing journey event: " + e.getMessage());
        }
    }
}
