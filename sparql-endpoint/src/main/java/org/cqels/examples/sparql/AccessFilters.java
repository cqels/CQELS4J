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
 * The request filters the launcher puts in front of the servlet. They are deliberately
 * small and generic so that, once {@code cqels-mcp} exposes a servlet mount point, the servlet
 * can run behind the MCP server's own bearer-token and origin filters instead and these are
 * simply not installed. They are public so that a program mounting the servlet in its own
 * container can install them too; the servlet itself checks neither tokens nor origins.
 */
public final class AccessFilters {

    private AccessFilters() { }

    /**
     * The request attribute {@link BearerToken} sets, to {@code Boolean.TRUE}, on a request it lets
     * through that may only read: one that presented the read token, or none, while a separate
     * update token is configured. {@link SparqlProtocolServlet} answers an update so marked with
     * 403. A request without the attribute is not restricted — the servlet checks no tokens itself.
     */
    public static final String READ_ONLY = "org.cqels.examples.sparql.readOnly";

    /**
     * {@code Authorization: Bearer <token>}, compared in constant time. A missing or wrong token
     * answers 401 with {@code WWW-Authenticate: Bearer}, so a browser or YASGUI shows a prompt
     * rather than a bare failure.
     *
     * <p>With an update token as well, the two are told apart: the update token may do anything,
     * the read token only read (the request is marked {@link #READ_ONLY}). With an update token and
     * <em>no</em> read token, a request carrying no token reads as well, and one carrying a token
     * that is not the update token is still a 401 — a client that believes it holds the update
     * token learns it does not, rather than being quietly demoted.
     */
    public static final class BearerToken implements Filter {
        private final byte[] read;     // null: reading needs no token
        private final byte[] update;   // null: the read token may update too

        public BearerToken(String token) {
            this(token, null);
        }

        /** @param readToken may be {@code null} when {@code updateToken} is set; not both */
        public BearerToken(String readToken, String updateToken) {
            if (readToken == null && updateToken == null) {
                throw new IllegalArgumentException("a bearer-token filter needs a token");
            }
            this.read = readToken == null ? null : readToken.getBytes(StandardCharsets.UTF_8);
            this.update = updateToken == null ? null : updateToken.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String header = req.getHeader("Authorization");
            byte[] presented = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? header.substring(7).trim().getBytes(StandardCharsets.UTF_8) : null;
            if (presented != null) {
                // both compared, whichever matches, so the time taken does not say which one did
                boolean isUpdate = update != null && MessageDigest.isEqual(update, presented);
                boolean isRead = read != null && MessageDigest.isEqual(read, presented);
                if (isUpdate || isRead) {
                    if (!isUpdate && update != null) {
                        req.setAttribute(READ_ONLY, Boolean.TRUE);
                    }
                    chain.doFilter(request, response);
                    return;
                }
            } else if (header == null && read == null) {
                req.setAttribute(READ_ONLY, Boolean.TRUE);   // open reads, token-protected writes
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
     * {@code Host} allow-list: the defence against DNS rebinding. An attacker's page on
     * {@code rebind.attacker.example} can have that name re-resolve to {@code 127.0.0.1}; its
     * requests are then same-origin as far as the browser is concerned — no {@code Origin}
     * header, {@code Sec-Fetch-Site: same-origin} — and it can read the answers. Found
     * reviewing #105: such a request returned the fleet's driver names. What the browser cannot
     * change is the {@code Host} it sends, which still names the attacker's domain, so a request
     * whose host is not one this endpoint is known by is refused (403).
     *
     * <p>The launcher installs it whenever no bearer token is set (a token defeats rebinding on
     * its own: the browser never attaches it for the attacker's page), or when
     * {@code CQELS_SPARQL_ALLOWED_HOSTS} is given. Names compare case-insensitively, without the
     * port and without IPv6 brackets.
     */
    public static final class Hosts implements Filter {
        private final List<String> allowed;

        public Hosts(List<String> allowed) {
            this.allowed = allowed.stream().map(Hosts::normalise).toList();
        }

        static String normalise(String host) {
            String h = host.strip().toLowerCase(java.util.Locale.ROOT);
            return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            String host = normalise(request.getServerName());
            if (!allowed.contains(host)) {
                HttpServletResponse resp = (HttpServletResponse) response;
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                resp.setContentType("text/plain; charset=UTF-8");
                resp.getWriter().write("host " + host + " is not allowed (CQELS_SPARQL_ALLOWED_HOSTS)\n");
                return;
            }
            chain.doFilter(request, response);
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
     * request from any <em>other</em> origin is refused with 403 before it reaches the servlet, and
 * so is a request with no {@code Origin} that the browser's Fetch Metadata
 * ({@code Sec-Fetch-Site: cross-site} or {@code same-site}) marks as sent by another site's page.
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
                // No Origin is not proof of a non-browser client: a cross-site <img> or link is a
                // GET without one. Browsers do label it with Fetch Metadata, so refuse what they
                // mark as coming from another site. "none" (typed into the address bar) and
                // "same-origin" pass, as does every client that sends no Sec-Fetch-Site at all.
                String site = req.getHeader("Sec-Fetch-Site");
                if ("cross-site".equalsIgnoreCase(site) || "same-site".equalsIgnoreCase(site)) {
                    refuse(resp, "a " + site + " browser request without an allowed Origin is refused");
                    return;
                }
                chain.doFilter(request, response);
                return;
            }
            if (!allowed.contains("*") && !allowed.contains(origin)) {
                refuse(resp, "origin " + origin + " is not allowed (CQELS_SPARQL_ALLOWED_ORIGINS)");
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

        private static void refuse(HttpServletResponse resp, String message) throws IOException {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().write(message + "\n");
        }
    }
}
