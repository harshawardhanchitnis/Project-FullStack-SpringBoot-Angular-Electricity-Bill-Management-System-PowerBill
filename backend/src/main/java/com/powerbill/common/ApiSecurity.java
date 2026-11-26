package com.powerbill.common;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class ApiSecurity {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, ObjectMapper mapper,
            @Value("${CORS_ALLOWED_ORIGINS:}") String configuredOrigins,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {
        List<String> origins = Arrays.stream(configuredOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList();
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowCredentials(true);
        cors.validateAllowCredentials();
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        UrlBasedCorsConfigurationSource corsSource = new UrlBasedCorsConfigurationSource();
        corsSource.registerCorsConfiguration("/api/**", cors);

        return http
                .cors(config -> config.configurationSource(corsSource))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> {
                    routes.requestMatchers("/api/v1/status", "/actuator/health/liveness",
                            "/actuator/health/readiness", "/actuator/prometheus").permitAll();
                    if (apiDocsEnabled) {
                        routes.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    routes.anyRequest().denyAll();
                })
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, error) ->
                                writeProblem(mapper, request, response, HttpStatus.UNAUTHORIZED,
                                        "Authentication is required for this resource."))
                        .accessDeniedHandler((request, response, error) ->
                                writeProblem(mapper, request, response, HttpStatus.FORBIDDEN,
                                        "Access to this resource is denied.")))
                .build();
    }

    private static void writeProblem(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put(CorrelationIdFilter.ATTRIBUTE, request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
