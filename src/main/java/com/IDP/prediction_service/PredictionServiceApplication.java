package com.IDP.prediction_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PredictionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PredictionServiceApplication.class, args);
	}
	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}
}
