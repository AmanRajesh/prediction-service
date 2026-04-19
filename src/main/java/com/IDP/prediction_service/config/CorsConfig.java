package com.IDP.prediction_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class CorsConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // allowedOriginPatterns is required instead of allowedOrigins("*")
                // when allowCredentials is true. This perfectly handles Ngrok's dynamic URLs.
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*") // Allows custom headers like 'ngrok-skip-browser-warning' and 'Authorization'
                .allowCredentials(true) // Required if you are passing JWT tokens or cookies
                .maxAge(3600); // Cache the pre-flight response for 1 hour
    }
}
