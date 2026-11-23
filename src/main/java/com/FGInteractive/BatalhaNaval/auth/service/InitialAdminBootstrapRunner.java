package com.FGInteractive.BatalhaNaval.auth.service;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs after migrations/repositories are initialized.
 * Disabled for CI and explicitly controllable by ADMIN_BOOTSTRAP_ENABLED.
 */
@Component
public class InitialAdminBootstrapRunner implements ApplicationRunner {
    private static final Logger logger =
        LoggerFactory.getLogger(InitialAdminBootstrapRunner.class);

    private final InitialAdminBootstrapService service;
    private final boolean enabled;
    private final String configuredFile;
    private final boolean localDefaultEnabled;
    private final Environment environment;

    public InitialAdminBootstrapRunner(InitialAdminBootstrapService service,
        @Value("${auth.admin-bootstrap.enabled:true}") boolean enabled,
        @Value("${auth.admin-bootstrap.credentials-file:}") String configuredFile,
        @Value("${auth.admin-bootstrap.local-default-enabled:true}") boolean localDefaultEnabled,
        Environment environment) {
        this.service = service;
        this.enabled = enabled;
        this.configuredFile = configuredFile;
        this.localDefaultEnabled = localDefaultEnabled;
        this.environment = environment;
    }

    @Override public void run(ApplicationArguments args) {
        if (!enabled) return;
        // Intentionally never allow fixed development credentials outside local profile.
        if (localDefaultEnabled && environment.acceptsProfiles(Profiles.of("local"))) {
            if (service.createLocalIfMissing())
                logger.warn("Local-only test admin provisioned. Change its password before sharing this database.");
            return;
        }
        Path output = configuredFile.isBlank()
            ? Path.of(System.getProperty("user.home"), ".battleship",
                "bootstrap-admin-credentials.txt")
            : Path.of(configuredFile);
        if (service.createIfMissing(output)) {
            logger.warn("Initial administrator was provisioned. Retrieve its credentials "
                + "from the local protected file at {}. Never share or commit it. "
                + "Log in and change the password, then securely remove the file.",
                output.toAbsolutePath().normalize());
        }
    }
}
