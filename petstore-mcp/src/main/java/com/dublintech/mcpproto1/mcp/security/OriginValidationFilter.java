package com.dublintech.mcpproto1.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rejects MCP requests whose {@code Origin} header names a site we don't trust, as the Streamable HTTP transport
 * spec requires ("Servers MUST validate the Origin header on all incoming connections to prevent DNS rebinding
 * attacks").
 *
 * <h2>What this protects: a local MCP server from a browser on the same machine</h2>
 * The threat is specific to servers that only the user's own machine (or private network) can reach and that
 * therefore rely on "nobody else can get here" instead of a login, as this gateway does on 127.0.0.1. The user's
 * browser is the weak point: it runs code from any website, and it is on the same machine.
 * <ol>
 *   <li>The user visits evil.example; its page loads in their local browser.</li>
 *   <li>evil.example's DNS is switched to answer 127.0.0.1.</li>
 *   <li>The page calls {@code http://evil.example:8081/mcp}. The browser sees a same-site request and allows it,
 *       but it now lands on this server, inheriting the trust that "local" was standing in for.</li>
 * </ol>
 * The browser still reports the page's real origin in the {@code Origin} header, so refusing untrusted origins
 * stops the attack. Requests without an {@code Origin} come from non-browser clients (mcp-remote, Claude Code, curl,
 * Bruno) and are allowed: they are not the rebinding threat, and authentication, not this filter, is what must
 * protect against them.
 *
 * <h2>Production (remote) MCP servers are different</h2>
 * A hosted server such as {@code https://mcp.example.com} has no "local browser" problem: it is on the internet and
 * already expects strangers. What makes rebinding dangerous is ambient trust, i.e. access granted just for being
 * local, or credentials the browser attaches automatically, such as cookies. A production server should have
 * neither: every request must carry an OAuth bearer token in the {@code Authorization} header, which a hostile page
 * cannot obtain or make the browser attach. So in production:
 * <ul>
 *   <li>Authentication on every request is the real control (token signature, issuer, audience, expiry, scopes).</li>
 *   <li>The Origin check is still worth keeping, since the spec says "all incoming connections", but as
 *       defence in depth: allow no-Origin callers (they must still authenticate), and treat a browser Origin as
 *       allowed only if the authenticated client registered that origin. That is per-client data looked up at
 *       request time, not a growing static list like this one.</li>
 *   <li>The same per-client data drives CORS, and the check usually lives at the edge (API gateway or WAF) rather
 *       than in each application.</li>
 *   <li>Never authenticate MCP calls with cookies. That brings back ambient credentials and, with them,
 *       cross-site request forgery, which only strict Origin checking would then stop.</li>
 * </ul>
 */
public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginValidationFilter.class);

    /** JSON-RPC error with no id, as the spec allows for this 403. -32600 is "Invalid Request". */
    private static final String FORBIDDEN_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Forbidden: invalid Origin\"}}";

    private final Set<String> allowedOrigins;

    public OriginValidationFilter(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream()
                .map(OriginValidationFilter::normalise)
                .filter(origin -> origin != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            // No Origin means the caller isn't a browser page, so DNS rebinding doesn't apply: allow.
            // This is the Claude Desktop route: Claude Desktop → stdio → mcp-remote (Node.js) → HTTP POST /mcp.
            // mcp-remote sends no Origin header, so it always takes this branch. Claude Code, curl and Bruno do too.
            chain.doFilter(request, response);
            return;
        }
        // A browser page sent this request: only the configured origins (the MCP Inspector by default) get through.
        String normalised = normalise(origin);
        if (normalised != null && allowedOrigins.contains(normalised)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rejected {} {} from disallowed Origin {}", request.getMethod(), request.getRequestURI(),
                origin.replaceAll("[\\r\\n\\t]", " "));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(FORBIDDEN_BODY);
    }

    /**
     * Reduces an origin to lower-case {@code scheme://host:port} so that equivalent spellings compare equal.
     * Returns null for anything that isn't a well-formed http(s) origin, including the literal "null" that
     * sandboxed pages and file:// documents send, so those never match an allowed entry.
     */
    static String normalise(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
