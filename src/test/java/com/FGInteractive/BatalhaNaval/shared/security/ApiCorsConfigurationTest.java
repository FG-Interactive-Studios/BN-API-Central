package com.FGInteractive.BatalhaNaval.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.*;

class ApiCorsConfigurationTest {
    private static final String FRONTEND = "https://naviiwar.web.app";

    private MockHttpServletResponse preflight(String allowedOrigins, String origin) throws Exception {
        var source = new SecurityConfig().apiCorsConfigurationSource(allowedOrigins);
        var request = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type, authorization");
        var response = new MockHttpServletResponse();
        new CorsFilter(source).doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsConfiguredFrontendPreflight() throws Exception {
        var response = preflight(" https://naviiwar.firebaseapp.com, " + FRONTEND, FRONTEND);
        assertEquals(200, response.getStatus());
        assertEquals(FRONTEND, response.getHeader("Access-Control-Allow-Origin"));
        assertNull(response.getHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    void rejectsUnlistedOrigin() throws Exception {
        var response = preflight(FRONTEND, "https://untrusted.example");
        assertEquals(403, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void emptyConfigurationDeniesCrossOrigin() throws Exception {
        assertEquals(403, preflight("", FRONTEND).getStatus());
    }

    @Test
    void rejectsWildcardConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> new SecurityConfig().apiCorsConfigurationSource("*"));
    }

    @Test
    void allowsSameOriginRequestsWithoutCorsHeaders() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/health");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new CorsFilter(new SecurityConfig().apiCorsConfigurationSource(""))
            .doFilter(request, response, chain);
        assertSame(request, chain.getRequest());
    }
}
