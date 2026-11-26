package com.powerbill;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class IdentityFlowTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName
            .parse("timescale/timescaledb:latest-pg17"
                    + "@sha256:2f5e6969faa5cd7f4b075d65f124e30dc50d456b7b807e5c1acd9d51ba7df51c")
            .asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName
            .parse("redis:7.2-alpine@sha256:2f66de68111e64c3927859f158f8e666066dae2b97d9f82770a6f37a5aa1c335"))
            .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> MAIL = new GenericContainer<>(DockerImageName
            .parse("axllent/mailpit:v1.31.3@sha256:ed9b00c609e77e99c79b93f1178255ebc271868920f2c69a8d166bd5634ed10d"))
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/readyz").forPort(8025));

    private static final String DEMO_PASSWORD = UUID.randomUUID().toString();
    private static final String CUSTOMER_PASSWORD = UUID.randomUUID().toString();
    private static final Pattern MAIL_TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry config) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        config.add("gridiq.auth.signing-secret", () -> HexFormat.of().formatHex(secret));
        config.add("gridiq.demo.enabled", () -> true);
        config.add("gridiq.demo.password", () -> DEMO_PASSWORD);
        config.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=gridiq,public");
        config.add("spring.datasource.username", POSTGRES::getUsername);
        config.add("spring.datasource.password", POSTGRES::getPassword);
        config.add("spring.data.redis.host", REDIS::getHost);
        config.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        config.add("spring.mail.host", MAIL::getHost);
        config.add("spring.mail.port", () -> MAIL.getMappedPort(1025));
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void registersOwnsAndUpdatesOnlyTheAuthenticatedCustomerAccount() throws Exception {
        String email = "ananya@example.test";
        var bad = call("POST", "/auth/register", registration(email, "123"), null, null);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).path("fieldErrors").has("mobileNumber")).isTrue();

        var registered = call("POST", "/auth/register", registration(email, "9876543210"), null, null);
        assertThat(registered.statusCode()).withFailMessage(registered.body()).isEqualTo(201);
        assertThat(registered.body()).doesNotContain(CUSTOMER_PASSWORD, "passwordHash", "password_hash");
        var data = json(registered);
        assertThat(data.path("account").path("accountType").asText()).isEqualTo("RESIDENTIAL");
        assertThat(data.path("premises").size()).isEqualTo(1);
        assertThat(data.path("meters").size()).isEqualTo(1);
        String meter = data.path("meters").get(0).path("id").asText();
        String owner = token(login(email, CUSTOMER_PASSWORD));
        String other = token(login("customer01@demo.powerbill.invalid", DEMO_PASSWORD));

        assertThat(call("GET", "/meters/" + meter, null, owner, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/meters/" + meter, null, other, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/meters/" + meter, null, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/ops/consumers", null, owner, null).statusCode()).isEqualTo(403);
        assertThat(json(call("GET", "/consumers/me/meters", null, owner, null)).size()).isEqualTo(1);

        var update = Map.of("billingEmail", "billing@example.test", "mobileNumber", "9876543210",
                "monthlyBudgetInr", 1300.25, "version", 0);
        assertThat(call("PUT", "/consumers/me", update, owner, null).statusCode()).isEqualTo(200);
        assertThat(call("PUT", "/consumers/me", update, owner, null).statusCode()).isEqualTo(409);

        String hash = db.sql("SELECT password_hash FROM users WHERE email=?").param(email).query(String.class).single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(CUSTOMER_PASSWORD);
        String confirmation = mailToken(email, "Welcome to PowerBill GridIQ");
        assertThat(call("POST", "/auth/verify-email", Map.of("token", confirmation), null, null).statusCode())
                .isEqualTo(204);
        assertThat(json(call("GET", "/auth/me", null, owner, null)).path("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void rotatesCookiesAndRevokesTheSessionFamilyOnReuse() throws Exception {
        var initial = login("customer02@demo.powerbill.invalid", DEMO_PASSWORD);
        String originalCookie = cookie(initial);
        assertThat(initial.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "SameSite=Lax");
        assertThat(initial.body()).doesNotContain("refreshToken", originalCookie.substring(originalCookie.indexOf('=') + 1));
        var replacement = call("POST", "/auth/refresh", null, null, originalCookie);
        assertThat(replacement.statusCode()).withFailMessage(replacement.body()).isEqualTo(200);
        String newCookie = cookie(replacement);
        assertThat(newCookie).isNotEqualTo(originalCookie);

        var reused = call("POST", "/auth/refresh", null, null, originalCookie);
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/refresh", null, null, newCookie).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/auth/me", null, token(replacement), null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/logout", null, null, null).statusCode()).isEqualTo(403);

        var fresh = login("customer02@demo.powerbill.invalid", DEMO_PASSWORD);
        assertThat(call("POST", "/auth/logout", null, null, cookie(fresh)).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/auth/me", null, token(fresh), null).statusCode()).isEqualTo(401);
    }

    @Test
    void filtersStaffDirectoryAndAuditsMutationsWithRoleChecks() throws Exception {
        String support = token(login("support@demo.powerbill.invalid", DEMO_PASSWORD));
        String field = token(login("field@demo.powerbill.invalid", DEMO_PASSWORD));
        String admin = token(login("admin@demo.powerbill.invalid", DEMO_PASSWORD));
        var search = call("GET", "/ops/consumers?q=demo&status=ACTIVE&type=COMMERCIAL&size=2",
                null, support, null);
        assertThat(search.statusCode()).withFailMessage(search.body()).isEqualTo(200);
        var page = json(search);
        assertThat(page.path("totalElements").asInt()).isEqualTo(4);
        assertThat(page.path("content").size()).isEqualTo(2);
        assertThat(call("GET", "/ops/consumers", null, field, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/ops/audit", null, support, null).statusCode()).isEqualTo(403);

        var account = page.path("content").get(0);
        String path = "/ops/consumers/" + account.path("id").asText();
        var changed = call("PATCH", path + "/status",
                Map.of("status", "SUSPENDED", "version", account.path("version").asLong()), support, null);
        assertThat(changed.statusCode()).withFailMessage(changed.body()).isEqualTo(200);
        assertThat(json(changed).path("status").asText()).isEqualTo("SUSPENDED");
        for (int i = 0; i < 25; i++) {
            var audit = call("GET", "/ops/audit?size=100", null, admin, null);
            assertThat(audit.statusCode()).isEqualTo(200);
            if (audit.body().contains("CONSUMER_STATUS_CHANGED")) {
                assertThat(audit.body()).contains("phase2-integration");
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("The audit event never reached the audit log");
    }

    @Test
    void limitsRepeatedLoginAttemptsAndKeepsTheResetResponseGeneric() throws Exception {
        String email = "customer03@demo.powerbill.invalid";
        for (int i = 0; i < 5; i++) {
            var rejected = call("POST", "/auth/login", Map.of("email", email, "password", "incorrect"), null, null);
            assertThat(rejected.statusCode()).withFailMessage(rejected.body()).isEqualTo(401);
        }
        var throttled = call("POST", "/auth/login", Map.of("email", email, "password", DEMO_PASSWORD), null, null);
        assertThat(throttled.statusCode()).withFailMessage(throttled.body()).isEqualTo(429);
        assertThat(throttled.headers().firstValue("Retry-After")).isPresent();

        String resetEmail = "customer04@demo.powerbill.invalid";
        var old = login(resetEmail, DEMO_PASSWORD);
        for (String target : new String[]{ resetEmail, "unregistered@example.test" }) {
            var requested = call("POST", "/auth/forgot-password", Map.of("email", target), null, null);
            assertThat(requested.statusCode()).isEqualTo(202);
            assertThat(requested.body()).isEmpty();
        }
        var reset = Map.of("token", mailToken(resetEmail, "Reset your GridIQ password"), "password", CUSTOMER_PASSWORD);
        assertThat(call("POST", "/auth/reset-password", reset, null, null).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/auth/reset-password", reset, null, null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/auth/me", null, token(old), null).statusCode()).isEqualTo(401);
        assertThat(login(resetEmail, CUSTOMER_PASSWORD).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        var response = call("POST", "/auth/login", Map.of("email", email, "password", password), null, null);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return response;
    }

    private Map<String, Object> registration(String email, String phone) {
        return Map.of("email", email, "password", CUSTOMER_PASSWORD, "fullName", "Ananya Kulkarni",
                "mobileNumber", phone, "premise", Map.of("label", "Home", "addressLine", "1 Sample Road",
                        "city", "Pune", "state", "Maharashtra", "pincode", "411001", "regionZone", "Pune"));
    }

    private HttpResponse<String> call(String method, String path, Object body, String bearer, String cookie)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10)).header("X-Request-Id", "phase2-integration");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (cookie != null) {
            request.header("Cookie", cookie).header("X-GridIQ-Session", "1");
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String token(HttpResponse<String> response) {
        return json(response).path("accessToken").asText();
    }

    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private String mailToken(String email, String subject) throws Exception {
        String base = "http://" + MAIL.getHost() + ":" + MAIL.getMappedPort(8025);
        for (int attempt = 0; attempt < 25; attempt++) {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/messages")).build(),
                    HttpResponse.BodyHandlers.ofString());
            for (JsonNode message : json(response).path("messages")) {
                if (message.path("Subject").asText().equals(subject)
                        && message.path("To").get(0).path("Address").asText().equals(email)) {
                    String path = "/api/v1/message/" + message.path("ID").asText();
                    var detail = client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    var token = MAIL_TOKEN.matcher(json(detail).path("Text").asText());
                    assertThat(token.find()).isTrue();
                    return token.group(1);
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("The expected email never reached Mailpit");
    }
}
