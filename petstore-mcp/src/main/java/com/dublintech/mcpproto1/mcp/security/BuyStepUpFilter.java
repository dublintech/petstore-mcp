package com.dublintech.mcpproto1.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Step-up authorization for buying (roadmap step 8): a {@code tools/call} of {@code buyPet} needs a token with
 * {@code petstore.buy}, and without one gets the MCP spec's scope challenge:
 * <pre>
 * 403 Forbidden
 * WWW-Authenticate: Bearer error="insufficient_scope", scope="petstore.read petstore.buy",
 *                   resource_metadata="http://localhost:8081/.well-known/oauth-protected-resource", ...
 * </pre>
 * mcp-remote answers it by authorizing again with the extra scope, so the browser shows the consent page for
 * {@code petstore.buy}, and then re-sends the call with the new token. Logins ask for {@code petstore.read} only
 * (see {@link ReadScopeChallengeFilter}), so the user approves buying when they first buy, not at login.
 *
 * <p>This has to be decided here, before the MCP transport answers: the transport replies to a {@code tools/call}
 * with a 200 event stream, after which the HTTP status can't change. So the filter reads the JSON-RPC body, which
 * it then hands on unchanged. The API checks {@code petstore.buy} as well; this filter is what turns a missing scope
 * into a consent prompt rather than a refused purchase.
 */
class BuyStepUpFilter extends OncePerRequestFilter {

    static final String BUY_TOOL = "buyPet";
    private static final int MAX_BODY = 1024 * 1024;

    private final JsonMapper json = JsonMapper.builder().build();
    private final String readScope;
    private final String buyScope;

    BuyStepUpFilter(String readScope, String buyScope) {
        this.readScope = readScope;
        this.buyScope = buyScope;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        if (user == null || user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_" + buyScope))) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        if (callsBuyTool(body)) {
            String metadata = UrlUtils.buildFullRequestUrl(request.getScheme(), request.getServerName(),
                    request.getServerPort(), "/.well-known/oauth-protected-resource", null);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, ("Bearer error=\"insufficient_scope\", "
                    + "error_description=\"Buying needs the %s scope\", scope=\"%s %s\", resource_metadata=\"%s\"")
                    .formatted(buyScope, readScope, buyScope, metadata));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(new BodyReplay(request, body), response);
    }

    /** True for a JSON-RPC {@code tools/call} of the buy tool, alone or inside a batch. */
    private boolean callsBuyTool(byte[] body) {
        try {
            JsonNode message = json.readTree(body);
            if (message.isArray()) {
                for (JsonNode element : message) {
                    if (isBuyCall(element)) {
                        return true;
                    }
                }
                return false;
            }
            return isBuyCall(message);
        } catch (JacksonException ex) {
            // Not JSON: the transport rejects it, with no tool called.
            return false;
        }
    }

    private static boolean isBuyCall(JsonNode message) {
        return "tools/call".equals(message.path("method").asString(null))
                && BUY_TOOL.equals(message.path("params").path("name").asString(null));
    }

    /** The request as it arrived, with the body it no longer has after being read here. */
    private static final class BodyReplay extends HttpServletRequestWrapper {

        private final byte[] body;

        BodyReplay(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
