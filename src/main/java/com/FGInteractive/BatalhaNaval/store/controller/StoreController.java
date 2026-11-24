package com.FGInteractive.BatalhaNaval.store.controller;

import com.FGInteractive.BatalhaNaval.store.dto.StoreDTO;
import com.FGInteractive.BatalhaNaval.store.service.StoreException;
import com.FGInteractive.BatalhaNaval.store.service.StoreService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/store")
public class StoreController {
    private final StoreService store;

    public StoreController(StoreService store) { this.store = store; }

    @GetMapping
    public StoreDTO catalog(@AuthenticationPrincipal Jwt jwt) {
        return store.catalog(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/purchase/{itemId}")
    public StoreDTO purchase(@AuthenticationPrincipal Jwt jwt, @PathVariable String itemId,
                             @RequestParam(defaultValue = "false") boolean premium) {
        return store.purchase(Long.parseLong(jwt.getSubject()), itemId, premium);
    }

    public record DemoTopupRequest(String packId, java.util.UUID requestId) {}

    @PostMapping("/demo-premium")
    public StoreDTO demoPremium(@AuthenticationPrincipal Jwt jwt, @RequestBody DemoTopupRequest request) {
        return store.demoTopup(Long.parseLong(jwt.getSubject()), request.packId(), request.requestId());
    }

    @PutMapping("/equip/{itemId}")
    public StoreDTO equip(@AuthenticationPrincipal Jwt jwt, @PathVariable String itemId) {
        return store.equip(Long.parseLong(jwt.getSubject()), itemId);
    }

    @RestControllerAdvice(assignableTypes = StoreController.class)
    public static class StoreErrors {
        @ExceptionHandler(StoreException.class)
        public ResponseEntity<Map<String, String>> handle(StoreException error) {
            return ResponseEntity.status(error.status()).body(
                Map.of("code", error.code(), "message", error.getMessage()));
        }
    }
}
