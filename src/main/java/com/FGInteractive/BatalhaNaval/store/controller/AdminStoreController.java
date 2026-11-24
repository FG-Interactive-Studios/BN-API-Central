package com.FGInteractive.BatalhaNaval.store.controller;

import com.FGInteractive.BatalhaNaval.store.service.AdminStoreService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/store/items")
public class AdminStoreController {
    private final AdminStoreService store;
    public AdminStoreController(AdminStoreService store){this.store=store;}
    public record AvailabilityRequest(Boolean enabled) {}

    @GetMapping
    public List<AdminStoreService.Item> list(@AuthenticationPrincipal Jwt jwt){
        return store.list(Long.parseLong(jwt.getSubject()));
    }
    @PutMapping("/{itemId}/availability")
    public AdminStoreService.Item availability(@AuthenticationPrincipal Jwt jwt,@PathVariable String itemId,
                                               @RequestBody AvailabilityRequest request){
        return store.change(Long.parseLong(jwt.getSubject()),itemId,request==null?null:request.enabled());
    }
}
