package com.FGInteractive.BatalhaNaval.shared.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.FGInteractive.BatalhaNaval.shared.dto.HealthResponse;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        HealthResponse response = new HealthResponse("UP", "Application is running");
        return ResponseEntity.ok(response);
    }
}
