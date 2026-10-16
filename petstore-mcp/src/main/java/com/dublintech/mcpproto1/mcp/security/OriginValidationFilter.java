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
 * <p>The attack: a web page the user visits re-points its own domain's DNS at 127.0.0.1, then uses the browser to
 * call this local server. The browser still sends that page's {@code Origin}, so checking it stops the attack.
 * Requests without an {@code Origin} come from non-browser clients (mcp-remote, Claude Code, curl, Bruno) and are
 * allowed; authentication, not this filter, is what protects against those.
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
