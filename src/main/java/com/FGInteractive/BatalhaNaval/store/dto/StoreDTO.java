package com.FGInteractive.BatalhaNaval.store.dto;

import java.util.List;

public record StoreDTO(int credits, List<Item> items) {
    public record Item(String id, String category, String name, String description,
                       int price, boolean enabled, boolean owned, boolean equipped) {}
}
