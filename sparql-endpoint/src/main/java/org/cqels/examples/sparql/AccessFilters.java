package org.cqels.examples.sparql;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The two request filters the launcher puts in front of the servlet. They are deliberately
 * small and generic so that, once {@code cqels-mcp} exposes a servlet mount point, the servlet
 * can run behind the MCP server's own bearer-token and origin filters instead and these are
 * simply not installed.
 */
final class AccessFilters {

    private AccessFilters() { }

    /**
     * {@code Authorization: Bearer <token>}, compared in constant time. A missing or wrong token
     * answers 401 with {@code WWW-Authenticate: Bearer}, so a browser or YASGUI shows a prompt
     * rather than a bare failure.
     */
    static final class BearerToken implements Filter {
        private final byte[] expected;

        BearerToken(String token) {
            this.expected = token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String header = req.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    && MessageDigest.isEqual(expected, header.substring(7).trim().getBytes(StandardCharsets.UTF_8))) {
                chain.doFilter(request, response);
                return;
            }
            resp.setHeader("WWW-Authenticate", "Bearer");
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().write("a bearer token is required\n");
        }
    }

    /**
     * CORS for browser clients such as YASGUI. Installed before the token filter, because a
     * preflight {@code OPTIONS} carries no {@code Authorization} header and must still get its
     * 204. An origin not on the list gets no CORS headers at all, which the browser then
     * refuses on its side; the request itself is not blocked here.
     */
    static final class Cors implements Filter {
        private final List<String> allowed;

        Cors(List<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String origin = req.getHeader("Origin");
            if (origin != null && (allowed.contains("*") || allowed.contains(origin))) {
                resp.setHeader("Access-Control-Allow-Origin", origin);
                resp.addHeader("Vary", "Origin");
                resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                resp.setHeader("Access-Control-Allow-Headers", "Accept, Content-Type, Authorization");
                resp.setHeader("Access-Control-Max-Age", "600");
                if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                    resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
