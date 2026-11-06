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
 * simply not installed. They are public so that a program mounting the servlet in its own
 * container can install them too; the servlet itself checks neither tokens nor origins.
 */
public final class AccessFilters {

    private AccessFilters() { }

    /**
     * {@code Authorization: Bearer <token>}, compared in constant time. A missing or wrong token
     * answers 401 with {@code WWW-Authenticate: Bearer}, so a browser or YASGUI shows a prompt
     * rather than a bare failure.
     */
    public static final class BearerToken implements Filter {
        private final byte[] expected;

        public BearerToken(String token) {
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
     * Origin check and CORS, installed on every launch (before the token filter, because a
     * preflight {@code OPTIONS} carries no {@code Authorization} header and must still get its
     * 204).
     *
     * <p>A request with no {@code Origin} header — curl, rdflib, Jena, any non-browser client —
     * passes untouched. A request whose {@code Origin} is on the allow-list (or the list is
     * {@code *}) gets the CORS headers, including {@code Access-Control-Expose-Headers} for
     * {@code X-CQELS-Truncated} and {@code Retry-After}, which a browser hides otherwise. A
     * request from any <em>other</em> origin is refused with 403 before it reaches the servlet.
     *
     * <p>Why refuse rather than merely withhold the CORS headers: a cross-origin
     * {@code application/x-www-form-urlencoded} POST is a "simple" request — the browser sends
     * it without a preflight and only hides the response. Any web page a user of a loopback
     * endpoint visits could otherwise post {@code update=DROP ALL} to {@code 127.0.0.1:8089}
     * and have it executed. Browsers attach {@code Origin} to every cross-origin POST, so this
     * check is what stops it.
     */
    public static final class Origins implements Filter {
        static final String EXPOSED = SparqlProtocolServlet.TRUNCATED_HEADER + ", Retry-After";

        private final List<String> allowed;

        public Origins(List<String> allowed) {
            this.allowed = List.copyOf(allowed);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String origin = req.getHeader("Origin");
            if (origin == null) {
                chain.doFilter(request, response);
                return;
            }
            if (!allowed.contains("*") && !allowed.contains(origin)) {
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                resp.setContentType("text/plain; charset=UTF-8");
                resp.getWriter().write("origin " + origin + " is not allowed (CQELS_SPARQL_ALLOWED_ORIGINS)\n");
                return;
            }
            resp.setHeader("Access-Control-Allow-Origin", origin);
            resp.addHeader("Vary", "Origin");
            resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Accept, Content-Type, Authorization");
            resp.setHeader("Access-Control-Expose-Headers", EXPOSED);
            resp.setHeader("Access-Control-Max-Age", "600");
            if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
