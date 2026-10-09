package com.FGInteractive.BatalhaNaval.shared.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtSigningKeyConfigurationTests {
    private final JwtSecurityConfig config = new JwtSecurityConfig();

    @Test
    void localProfileGeneratesStrongEphemeralKeyWhenSecretMissing() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        byte[] first = config.jwtSigningKey("", local).getEncoded();
        byte[] second = config.jwtSigningKey(" ", local).getEncoded();

        assertEquals(48, first.length);
        assertEquals(48, second.length);
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void missingSecretFailsClosedWithoutExplicitLocalProfile() {
        assertThrows(IllegalStateException.class,
            () -> config.jwtSigningKey("", new MockEnvironment()));

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,
            () -> config.jwtSigningKey("", prod));

        MockEnvironment ci = new MockEnvironment();
        ci.setActiveProfiles("ci");
        assertThrows(IllegalStateException.class,
            () -> config.jwtSigningKey("", ci));
    }

    @Test
    void explicitSecretIsUsedInLocalAndProduction() {
        byte[] secret = new byte[48];
        Arrays.fill(secret, (byte) 37);
        String base64 = Base64.getEncoder().encodeToString(secret);
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertArrayEquals(secret, config.jwtSigningKey(base64, local).getEncoded());
        assertArrayEquals(secret, config.jwtSigningKey(base64, new MockEnvironment()).getEncoded());
    }

    @Test
    void invalidOrShortExplicitSecretsFailEvenInLocalProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThrows(IllegalStateException.class,
            () -> config.jwtSigningKey("not base64%", local));
        assertThrows(IllegalStateException.class,
            () -> config.jwtSigningKey(Base64.getEncoder().encodeToString(new byte[16]), local));
    }
}
