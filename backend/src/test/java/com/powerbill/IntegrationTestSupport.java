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

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shares one set of containers and one Spring context across the integration suites. The containers start once
 * per JVM and Testcontainers removes them when the test run ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
abstract class IntegrationTestSupport {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName
            .parse("timescale/timescaledb:latest-pg17"
                    + "@sha256:2f5e6969faa5cd7f4b075d65f124e30dc50d456b7b807e5c1acd9d51ba7df51c")
            .asCompatibleSubstituteFor("postgres"));

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName
            .parse("redis:7.2-alpine@sha256:2f66de68111e64c3927859f158f8e666066dae2b97d9f82770a6f37a5aa1c335"))
            .withExposedPorts(6379);

    static final GenericContainer<?> MAIL = new GenericContainer<>(DockerImageName
            .parse("axllent/mailpit:v1.31.3@sha256:ed9b00c609e77e99c79b93f1178255ebc271868920f2c69a8d166bd5634ed10d"))
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/readyz").forPort(8025));

    static final String DEMO_PASSWORD = UUID.randomUUID().toString();
    static final String CUSTOMER_PASSWORD = UUID.randomUUID().toString();
    private static final Pattern MAIL_TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

    static {
        POSTGRES.start();
        REDIS.start();
        MAIL.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry config) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        config.add("gridiq.auth.signing-secret", () -> HexFormat.of().formatHex(secret));
        config.add("gridiq.demo.enabled", () -> true);
        config.add("gridiq.demo.password", () -> DEMO_PASSWORD);
        config.add("gridiq.ml.enabled", () -> false);
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
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    HttpResponse<String> login(String email, String password) throws Exception {
        var response = call("POST", "/auth/login", Map.of("email", email, "password", password), null, null);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return response;
    }

    /** Demo sessions are reused across suites so the login rate limit (five a minute) is not exhausted. */
    private static final java.util.Map<String, String> DEMO_TOKENS = new java.util.concurrent.ConcurrentHashMap<>();

    String demoToken(String name) throws Exception {
        String cached = DEMO_TOKENS.get(name);
        if (cached != null && call("GET", "/auth/me", null, cached, null).statusCode() == 200) {
            return cached;
        }
        String fresh = token(login(name + "@demo.powerbill.invalid", DEMO_PASSWORD));
        DEMO_TOKENS.put(name, fresh);
        return fresh;
    }

    Map<String, Object> registration(String email, String phone) {
        return Map.of("email", email, "password", CUSTOMER_PASSWORD, "fullName", "Ananya Kulkarni",
                "mobileNumber", phone, "premise", Map.of("label", "Home", "addressLine", "1 Sample Road",
                        "city", "Pune", "state", "Maharashtra", "pincode", "411001", "regionZone", "Pune"));
    }

    HttpResponse<String> call(String method, String path, Object body, String bearer, String cookie)
            throws Exception {
        return call(method, path, body, bearer, cookie, Map.of());
    }

    HttpResponse<String> call(String method, String path, Object body, String bearer, String cookie,
            Map<String, String> headers) throws Exception {
        var request = request(method, path, body, bearer, cookie, headers);
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<byte[]> bytes(String path, String bearer) throws Exception {
        return client.send(request("GET", path, null, bearer, null, Map.of()), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest request(String method, String path, Object body, String bearer, String cookie,
            Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30)).header("X-Request-Id", "phase2-integration");
        headers.forEach(request::header);
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
        return request.build();
    }

    JsonNode json(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    String token(HttpResponse<String> response) {
        return json(response).path("accessToken").asText();
    }

    static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    String mailToken(String email, String subject) throws Exception {
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

    /** Extracts the text of every page so tests can check the figures a customer actually sees in a PDF. */
    static String pdfText(byte[] pdf) throws Exception {
        try (var reader = new PdfReader(pdf)) {
            var extractor = new PdfTextExtractor(reader);
            var text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }

    boolean mailArrived(String email, String subjectPrefix) throws Exception {
        String base = "http://" + MAIL.getHost() + ":" + MAIL.getMappedPort(8025);
        for (int attempt = 0; attempt < 40; attempt++) {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/messages?limit=500")).build(),
                    HttpResponse.BodyHandlers.ofString());
            for (JsonNode message : json(response).path("messages")) {
                if (message.path("Subject").asText().startsWith(subjectPrefix)
                        && message.path("To").get(0).path("Address").asText().equals(email)) {
                    return true;
                }
            }
            Thread.sleep(250);
        }
        return false;
    }
}
