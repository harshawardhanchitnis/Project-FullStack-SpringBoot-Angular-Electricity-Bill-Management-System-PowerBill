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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class ApiSecurity {
    private static final String[] PUBLIC_AUTH_POSTS = {
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
        "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/verify-email"
    };

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
        cors.setAllowedHeaders(List.of(
                "Accept", "Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id", "X-GridIQ-Session"));
        cors.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        UrlBasedCorsConfigurationSource corsSource = new UrlBasedCorsConfigurationSource();
        corsSource.registerCorsConfiguration("/api/**", cors);

        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var jwt = new JwtAuthenticationConverter();
        jwt.setJwtGrantedAuthoritiesConverter(authorities);
        var unauthorized = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, error) ->
                writeProblem(mapper, request, response, HttpStatus.UNAUTHORIZED,
                        "Authentication is required for this resource.");

        return http
                .cors(config -> config.configurationSource(corsSource))
                .csrf(config -> config.ignoringRequestMatchers(PUBLIC_AUTH_POSTS)
                        .ignoringRequestMatchers("/api/v1/auth/change-password", "/api/v1/auth/me/**",
                                "/api/v1/consumers/**", "/api/v1/ops/**", "/api/v1/meters/**"))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(config -> config.authenticationEntryPoint(unauthorized)
                        .jwt(jwtConfig -> jwtConfig.jwtAuthenticationConverter(jwt)))
                .authorizeHttpRequests(routes -> {
                    routes.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_POSTS).permitAll();
                    routes.requestMatchers(HttpMethod.GET, "/api/v1/status", "/actuator/health/liveness",
                            "/actuator/health/readiness", "/actuator/prometheus").permitAll();
                    if (apiDocsEnabled) {
                        routes.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    routes.requestMatchers("/api/v1/consumers/**").hasRole("CUSTOMER");
                    routes.requestMatchers("/api/v1/ops/consumers/**")
                            .hasAnyRole("BILLING_OFFICER", "SUPPORT_AGENT", "SUPER_ADMIN");
                    routes.requestMatchers("/api/v1/ops/audit").hasRole("SUPER_ADMIN");
                    routes.requestMatchers("/api/v1/auth/me", "/api/v1/auth/me/preferences",
                            "/api/v1/auth/change-password", "/api/v1/meters/**").authenticated();
                    routes.anyRequest().denyAll();
                })
                .exceptionHandling(config -> config
                        .authenticationEntryPoint(unauthorized)
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
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
