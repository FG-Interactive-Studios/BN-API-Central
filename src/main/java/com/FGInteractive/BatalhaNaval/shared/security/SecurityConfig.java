package com.FGInteractive.BatalhaNaval.shared.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UrlBasedCorsConfigurationSource apiCorsConfigurationSource) throws Exception {
        http
            .cors(cors -> cors.configurationSource(apiCorsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                // WebSocket handshakes cannot set an Authorization header in browsers.
                // The handshake interceptor requires a single-use, session-bound ticket.
                .requestMatchers(HttpMethod.GET, "/ws").permitAll()
                // Only numeric public-profile paths are anonymous; "/api/users/me" stays protected.
                .requestMatchers(request -> "GET".equals(request.getMethod())
                    && request.getRequestURI().substring(request.getContextPath().length())
                        .matches("/api/users/[0-9]+")).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                    "/api/auth/refresh").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }

    // The browser REST API and WebSocket use the same explicit frontend allowlist.
    // An empty list preserves same-origin development and denies cross-origin requests.
    @Bean
    UrlBasedCorsConfigurationSource apiCorsConfigurationSource(
            @Value("${realtime.allowed-origins:}") String configuredOrigins) {
        List<String> origins = Arrays.stream(configuredOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .distinct()
            .toList();
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException("Wildcard CORS origins are not allowed");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
