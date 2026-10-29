package com.demo.WechatBot;

import com.demo.WechatBot.service.AdminUserService;
import com.demo.WechatBot.service.AdminUserStore;
import com.demo.WechatBot.service.BotAccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:wechatbot-security-test?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-test-query=SELECT 1",
        "ai.deepseek.api-key=test-deepseek-key",
        "ai.dashscope.api-key=test-dashscope-key",
        "app.security.bootstrap-admin.username=admin",
        "app.security.bootstrap-admin.password=admin-test-password",
        "app.security.login-max-failures=5",
        "app.security.login-lock-seconds=900"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminUserService userService;

    @Autowired
    private AdminUserStore userStore;

    @Autowired
    private BotAccountStore botAccountStore;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminUserStore.AdminUser admin;
    private AdminUserStore.AdminUser alice;
    private AdminUserStore.AdminUser bob;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM bot_accounts");
        jdbc.update("DELETE FROM admin_users WHERE username <> 'admin'");
        admin = userStore.findAnyAdmin().orElseThrow();
        alice = userService.createUser("alice", "alice-password-123", AdminUserStore.ROLE_USER);
        bob = userService.createUser("bob", "bob-password-123", AdminUserStore.ROLE_USER);
    }

    @Test
    void unauthenticatedRequestsAreRejectedAndPublicEndpointsStayAvailable() throws Exception {
        mockMvc.perform(get("/login.html")).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.headerName").isString());
        mockMvc.perform(get("/api/bots")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html"));
    }

    @Test
    void usersOnlySeeAndDeleteTheirOwnBots() throws Exception {
        MockHttpSession aliceSession = login("alice", "alice-password-123");
        botAccountStore.upsertOnline(alice.id(), "bot-a", "wechat-a", "token-a", "https://ilinkai.weixin.qq.com");
        botAccountStore.upsertOnline(bob.id(), "bot-b", "wechat-b", "token-b", "https://ilinkai.weixin.qq.com");
        seedBotSessionData("bot-a");
        seedBotSessionData("bot-b");

        mockMvc.perform(get("/api/bots").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].botId", is("bot-a")));

        mockMvc.perform(post("/api/bots/bot-b/remove")
                        .session(aliceSession).with(csrf()))
                .andExpect(status().isNotFound());
        assertTrue(botAccountStore.findByBotId("bot-b").isPresent());

        mockMvc.perform(post("/api/bots/bot-a/remove").session(aliceSession))
                .andExpect(status().isForbidden());
        assertTrue(botAccountStore.findByBotId("bot-a").isPresent());

        mockMvc.perform(post("/api/bots/bot-a/remove")
                        .session(aliceSession).with(csrf()))
                .andExpect(status().isOk());
        assertTrue(botAccountStore.findByBotId("bot-a").isEmpty());
        assertEquals(0, countSessionRows("bot-a"));
        assertEquals(6, countSessionRows("bot-b"));
    }

    @Test
    void adminUserManagementRequiresAdminRole() throws Exception {
        MockHttpSession userSession = login("alice", "alice-password-123");
        mockMvc.perform(get("/api/admin/users").session(userSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin.html").session(userSession))
                .andExpect(status().isForbidden());

        MockHttpSession adminSession = login("admin", "admin-test-password");
        mockMvc.perform(get("/api/admin/users").session(adminSession))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users")
                        .session(adminSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"carol","password":"carol-password-123","role":"USER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username", is("carol")));
        login("carol", "carol-password-123");
    }

    @Test
    void disablingAccountInvalidatesExistingSession() throws Exception {
        MockHttpSession aliceSession = login("alice", "alice-password-123");
        userService.setEnabled(admin.id(), alice.id(), false);

        mockMvc.perform(get("/api/bots").session(aliceSession))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetInvalidatesExistingSession() throws Exception {
        MockHttpSession aliceSession = login("alice", "alice-password-123");
        userService.resetPassword(alice.id(), "alice-new-password-123");

        mockMvc.perform(get("/api/bots").session(aliceSession))
                .andExpect(status().isUnauthorized());
        login("alice", "alice-new-password-123");
    }

    @Test
    void repeatedLoginFailuresAreRateLimited() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .with(csrf())
                            .param("username", "rate-user")
                            .param("password", "wrong-password"))
                    .andExpect(status().is(i < 5 ? 401 : 429));
        }
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "rate-user")
                        .param("password", "wrong-password"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void publicRegistrationCreatesPlainUserAccount() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"carol","password":"carol-password-123"}
                                """))
                .andExpect(status().isOk());

        AdminUserStore.AdminUser carol = userStore.findByUsername("carol").orElseThrow();
        assertEquals(AdminUserStore.ROLE_USER, carol.role());
        assertTrue(carol.enabled());

        MockHttpSession session = login("carol", "carol-password-123");
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("USER")));
        mockMvc.perform(get("/api/admin/users").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationRejectsWeakPasswordAndDuplicateUsername() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"dave","password":"short"}
                                """))
                .andExpect(status().isBadRequest());
        assertTrue(userStore.findByUsername("dave").isEmpty());

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"alice-other-password"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void seedBotSessionData(String botId) {
        String session = botId + ":user";
        jdbc.update("""
                INSERT INTO bot_personas (session_key, status, name, persona_text)
                VALUES (?, 'READY', '测试伙伴', '测试人设')
                """, session);
        jdbc.update("INSERT INTO chat_memory (session_id, messages_json) VALUES (?, '[]')", session);
        jdbc.update("INSERT INTO conversations (session_id, role, content) VALUES (?, 'user', '你好')", session);
        jdbc.update("INSERT INTO user_profiles (session_id, profile) VALUES (?, '测试画像')", session);
        jdbc.update("INSERT INTO relationship_states (session_id, state_text) VALUES (?, '测试状态')", session);
        jdbc.update("""
                INSERT INTO conversation_contexts (session_key, bot_id, user_id, context_token)
                VALUES (?, ?, 'user', 'token')
                """, session, botId);
    }

    private int countSessionRows(String botId) {
        String pattern = botId + ":%";
        return count("bot_personas", "session_key", pattern)
                + count("chat_memory", "session_id", pattern)
                + count("conversations", "session_id", pattern)
                + count("user_profiles", "session_id", pattern)
                + count("relationship_states", "session_id", pattern)
                + jdbc.queryForObject(
                        "SELECT COUNT(*) FROM conversation_contexts WHERE bot_id = ?",
                        Integer.class, botId);
    }

    private int count(String table, String column, String pattern) {
        Integer result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE ?",
                Integer.class, pattern);
        return result != null ? result : 0;
    }
}
