package com.dublintech.mcpproto1.mcp.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A stand-in for petstore-auth in gateway tests: it publishes a discovery document and a JWKS on a random local
 * port, and signs test tokens with its key. The gateway runs its real resource server configuration against it,
 * so the tests cover the same signature, issuer, expiry, audience and scope checks as production. Its token
 * endpoint answers the gateway's own client_credentials and token exchange requests.
 *
 * <p>Test classes that start the gateway call {@link #register} from a {@code @DynamicPropertySource} method.
 */
public final class TestAuthorizationServer {

    public static final String RESOURCE = "http://localhost:8081/mcp";
    /** A user whose tokens petstore-auth no longer knows, as after a restart: exchanging them fails. */
    public static final String FORGOTTEN_USER = "forgotten-user";
    public static final String GATEWAY_BASIC_AUTH = "Basic " + Base64.getEncoder().encodeToString(
            "petstore-gateway:gateway-dev-secret-not-for-production".getBytes(StandardCharsets.UTF_8));

    private static final List<Map<String, String>> EXCHANGES = new CopyOnWriteArrayList<>();
    private static final RSAKey KEY = generateKey();
    // Same key ID, different key: what a forger without the real private key would have to use.
    private static final RSAKey FORGED_KEY = generateKey();
    private static final HttpServer SERVER = start();

    private TestAuthorizationServer() {
    }

    public static String issuer() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestAuthorizationServer::issuer);
        registry.add("spring.security.oauth2.client.provider.petstore-auth.token-uri", () -> issuer() + "/token");
    }

    /** Every token exchange request received so far, as form parameters plus the Authorization header. */
    public static List<Map<String, String>> exchanges() {
        return List.copyOf(EXCHANGES);
    }

    /** A valid token for user1 with petstore.read, as petstore-auth would issue for the gateway. */
    public static String validToken() {
        return token(claims -> { });
    }

    /** A token built from valid defaults, with {@code changes} applied on top. */
    public static String token(Consumer<JwtClaimsSet.Builder> changes) {
        return sign(KEY, changes);
    }

    /** Looks valid in every way, but is signed with a key the authorization server never published. */
    public static String forgedToken() {
        return sign(FORGED_KEY, claims -> { });
    }

    private static String sign(RSAKey key, Consumer<JwtClaimsSet.Builder> changes) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer())
                .subject("user1")
                .audience(List.of(RESOURCE))
                .claim("scope", List.of("petstore.read"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        changes.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/", exchange -> respond(exchange, """
                    {"issuer":"%s","jwks_uri":"%s/jwks"}""".formatted(issuer(server), issuer(server))));
            server.createContext("/jwks", exchange -> respond(exchange, new JWKSet(KEY).toPublicJWKSet().toString()));
            server.createContext("/token", TestAuthorizationServer::token);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String issuer(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * The token endpoint, for the two grants the gateway uses: client_credentials gives the gateway's own token,
     * token exchange gives an API token for the subject token's user, as petstore-auth would.
     */
    private static void token(HttpExchange exchange) throws IOException {
        if (!GATEWAY_BASIC_AUTH.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            String[] parts = pair.split("=", 2);
            form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        if ("client_credentials".equals(form.get("grant_type"))) {
            respond(exchange, tokenResponse(sign(KEY, claims -> claims.subject("petstore-gateway")
                    .audience(List.of("petstore-gateway")).claims(c -> c.remove("scope")))));
            return;
        }
        form.put("Authorization", exchange.getRequestHeaders().getFirst("Authorization"));
        EXCHANGES.add(form);
        try {
            JWTClaimsSet user = SignedJWT.parse(form.get("subject_token")).getJWTClaimsSet();
            if (FORGOTTEN_USER.equals(user.getSubject())) {
                respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            respond(exchange, tokenResponse(sign(KEY, claims -> claims.subject(user.getSubject())
                    .audience(List.of(form.get("resource")))
                    .claim("scope", user.getClaim("scope"))
                    .claim("act", Map.of("sub", "petstore-gateway")))));
        } catch (ParseException e) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
        }
    }

    private static String tokenResponse(String accessToken) {
        return "{\"access_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                .formatted(accessToken) + "\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}";
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        respond(exchange, 200, json);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
