package com.aea.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end authentication against a real PostgreSQL, exercising the whole
 * chain: routing lookup, tenant binding, RLS-scoped user load, BCrypt check,
 * token issue, and refresh rotation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
class AuthFlowTest {

    private static final String ASHA = "asha@techstore.test";
    private static final String PASSWORD = "demo1234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode login(String email, String password) throws Exception {
        var body = """
                {"email": "%s", "password": "%s"}""".formatted(email, password);
        var result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a seeded user can log in and receives both tokens")
    void loginSucceeds() throws Exception {
        JsonNode tokens = login(ASHA, PASSWORD);
        assertFalse(tokens.path("accessToken").asText().isBlank());
        assertFalse(tokens.path("refreshToken").asText().isBlank());
        assertEquals("Bearer", tokens.path("tokenType").asText());
        assertTrue(tokens.path("expiresIn").asLong() > 0);
    }

    @Test
    @DisplayName("the token carries the caller's real roles and permissions")
    void meReflectsSeededAuthorities() throws Exception {
        String token = login(ASHA, PASSWORD).path("accessToken").asText();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.tenantId").value("11111111-1111-1111-1111-111111111111"))
           .andExpect(jsonPath("$.roles[0]").value("MANAGER"))
           // A MANAGER can read sales...
           .andExpect(jsonPath("$.permissions", hasItemString("READ_SALES")))
           // ...and must not hold READ_SALARY. No system role grants it, which
           // is what makes demo query 4 a structural denial rather than a
           // special case in application code.
           .andExpect(jsonPath("$.permissions", not(hasItemString("READ_SALARY"))));
    }

    @Test
    @DisplayName("an employee holds neither sales nor salary access")
    void employeeIsNarrowlyScoped() throws Exception {
        String token = login("sam@techstore.test", PASSWORD).path("accessToken").asText();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.permissions", hasItemString("READ_POLICY")))
           .andExpect(jsonPath("$.permissions", not(hasItemString("READ_SALES"))))
           .andExpect(jsonPath("$.permissions", not(hasItemString("READ_SALARY"))));
    }

    @Test
    @DisplayName("the HR role is the only holder of READ_SALARY")
    void hrRoleCarriesSalaryAccess() throws Exception {
        String token = login("hr@techstore.test", PASSWORD).path("accessToken").asText();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.permissions", hasItemString("READ_SALARY")));
    }

    @Test
    @DisplayName("a wrong password is rejected")
    void wrongPasswordRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "not-the-password"}""".formatted(ASHA)))
           .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown email fails the same way as a wrong password")
    void unknownUserIsIndistinguishable() throws Exception {
        var unknown = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@techstore.test", "password": "demo1234"}"""))
                .andExpect(status().isUnauthorized()).andReturn();

        var wrongPassword = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrong"}""".formatted(ASHA)))
                .andExpect(status().isUnauthorized()).andReturn();

        // Identical bodies: a differing message turns the login form into a
        // user enumerator for the whole platform.
        assertEquals(
                json.readTree(unknown.getResponse().getContentAsString()).path("detail").asText(),
                json.readTree(wrongPassword.getResponse().getContentAsString()).path("detail").asText());
    }

    @Test
    @DisplayName("no token means no access to a protected endpoint")
    void protectedEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void tamperedTokenRejected() throws Exception {
        String token = login(ASHA, PASSWORD).path("accessToken").asText();
        // Flip a character in the signature.
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "B" : "A");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tampered))
           .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh rotates: the old token stops working")
    void refreshRotatesTokens() throws Exception {
        String firstRefresh = login(ASHA, PASSWORD).path("refreshToken").asText();

        var refreshed = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}""".formatted(firstRefresh)))
                .andExpect(status().isOk()).andReturn();
        String secondRefresh = json.readTree(refreshed.getResponse().getContentAsString())
                .path("refreshToken").asText();
        assertNotEquals(firstRefresh, secondRefresh, "refresh tokens must rotate");

        // Replaying the consumed token must fail, and the reuse is treated as
        // theft: the replacement is revoked too.
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}""".formatted(firstRefresh)))
           .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}""".formatted(secondRefresh)))
           .andExpect(status().isUnauthorized());
    }

    private static org.hamcrest.Matcher<Iterable<? super String>> hasItemString(String value) {
        return org.hamcrest.Matchers.hasItem(value);
    }

    private static <T> org.hamcrest.Matcher<T> not(org.hamcrest.Matcher<T> m) {
        return org.hamcrest.Matchers.not(m);
    }
}
