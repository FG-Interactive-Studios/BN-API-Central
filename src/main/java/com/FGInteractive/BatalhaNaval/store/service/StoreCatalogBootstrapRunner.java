package com.FGInteractive.BatalhaNaval.store.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Run after Flyway, before serving the application. */
@Component
@ConditionalOnProperty(name="store.catalog.bootstrap-enabled",havingValue="true",matchIfMissing=true)
public class StoreCatalogBootstrapRunner implements ApplicationRunner {
    private final StoreCatalogBootstrapService catalog;
    public StoreCatalogBootstrapRunner(StoreCatalogBootstrapService catalog) {
        this.catalog=catalog;
    }
    @Override public void run(ApplicationArguments args) {
        catalog.synchronize();
    }
}
