package com.example.attendance;

import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PwaAndSecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setup() {
        rateLimiterService.resetAll();
    }

    @Test
    void servesPwaManifestIconsServiceWorkerAndStaticPages() throws Exception {
        mockMvc.perform(get("/manifest.webmanifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MEC Team Attendance"))
                .andExpect(jsonPath("$.display").value("standalone"))
                .andExpect(jsonPath("$.icons.length()").value(3));

        mockMvc.perform(get("/service-worker.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("url.pathname.startsWith('/api/')")))
                .andExpect(content().string(containsString("url.pathname.startsWith('/admin')")));

        mockMvc.perform(get("/assets/icon-192.png")).andExpect(status().isOk());
        mockMvc.perform(get("/assets/icon-512.png")).andExpect(status().isOk());
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/worker")).andExpect(status().isOk());
        mockMvc.perform(get("/worker/")).andExpect(status().isOk());
        mockMvc.perform(get("/worker/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/employee")).andExpect(status().isOk());
        mockMvc.perform(get("/employee/")).andExpect(status().isOk());
        mockMvc.perform(get("/admin")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/index.html")).andExpect(status().isOk());
    }

    @Test
    void appliesSecurityAndNoStoreHeadersOnAuthenticatedEndpoints() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "TestAdminPassword123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie adminCookie = res.getResponse().getCookie(CookieHelper.ADMIN_SESSION_COOKIE);

        mockMvc.perform(get("/api/v1/admin/workers").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=(self)")))
                .andExpect(header().string("Permissions-Policy", containsString("geolocation=(self)")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }
}
