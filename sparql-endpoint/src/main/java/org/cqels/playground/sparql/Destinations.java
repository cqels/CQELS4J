package org.cqels.playground.sparql;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where {@code SERVICE} and {@code LOAD} may send a request: {@link EndpointOptions#federationDestinations},
 * enforced by {@link TimedServiceClient} for SERVICE and by the servlet's own fetch for LOAD (and the
 * JSON-LD contexts a LOAD source names), on every request and every redirect.
 *
 * <p><strong>Why.</strong> With federation on, the endpoint sent a request to any {@code http} or
 * {@code https} address a client named — {@code http://127.0.0.1:…}, a cloud metadata service at
 * {@code 169.254.169.254}, a database's admin port on the private network — and, for SERVICE, with
 * bindings from the graph in it. The client cannot reach those addresses itself; this server can.
 *
 * <p><strong>The entries.</strong>
 * <ul>
 *   <li>{@code public}, the default: any host <em>every</em> address of which is a public unicast
 *       address ({@link #isPublic}). Loopback, private, link-local, shared (CGNAT), multicast,
 *       reserved and documentation ranges are refused, as are IPv6 forms that carry one of them
 *       (IPv4-mapped, NAT64, 6to4).</li>
 *   <li>an origin, {@code http://host} or {@code https://host:port}: that scheme, host and port
 *       (the scheme's default when none is given), whatever its addresses — the way to reach one
 *       endpoint on a private network, {@code http://localhost:3030} say. Nothing else of the URL
 *       may be given: an origin is the unit a redirect is checked against.</li>
 *   <li>{@code *}: anywhere, as before this list existed. For a closed network whose every
 *       address is meant to be reachable.</li>
 * </ul>
 * A destination an origin entry names is reached without looking at its addresses; one that is
 * reached only under {@code public} has its addresses checked; one that neither admits is refused.
 *
 * <p><strong>Where the addresses are checked.</strong> In the DNS resolver the HTTP client connects
 * through ({@link Dns}): the addresses checked are the addresses the socket is opened to. A check
 * made beforehand, by a lookup of its own, would let a name that answers a public address to the
 * check and a private one to the connection (DNS rebinding) through. An IP literal is checked the
 * same way — the client hands it to the resolver too — so {@code http://2130706433/},
 * {@code http://0.0.0.0/} or {@code http://[::ffff:127.0.0.1]/} are refused as {@code 127.0.0.1} is.
 *
 * <p><strong>Through an HTTP proxy</strong> (the JVM's {@code http.proxyHost} and its kin, which the
 * clients honour), the proxy resolves the destination, not this server, so its addresses cannot be
 * checked here: a destination admitted only by {@code public} is then refused. List it as an
 * origin, or let the proxy enforce its own policy and use {@code *}.
 */
final class Destinations {

    /** The keyword for "any host whose every address is public". */
    static final String PUBLIC = "public";
    /** The keyword for "anywhere". */
    static final String ANYWHERE = "*";

    /** The default, {@code public}. */
    static final Destinations PUBLIC_ONLY = parse(List.of(PUBLIC));
    /** {@code *}: no restriction. */
    static final Destinations ANY = parse(List.of(ANYWHERE));

    /** What {@link #decide} concludes for one destination. */
    enum Decision {
        /** Named by an origin entry, or {@code *}: reached without looking at its addresses. */
        ADMITTED,
        /** Admitted only by {@code public}: reached only if every address it resolves to is public. */
        IF_PUBLIC,
        /** Admitted by nothing. */
        REFUSED
    }

    private final boolean anywhere;
    private final boolean publicHosts;
    private final Set<String> origins;   // "scheme://host:port", lower case, IPv6 without brackets
    private final List<String> entries;

    private Destinations(boolean anywhere, boolean publicHosts, Set<String> origins, List<String> entries) {
        this.anywhere = anywhere;
        this.publicHosts = publicHosts;
        this.origins = origins;
        this.entries = entries;
    }

    /**
     * The policy {@code entries} describe; an empty list is {@code public}.
     *
     * @throws IllegalArgumentException for an entry that is neither keyword nor an http(s) origin
     */
    static Destinations parse(List<String> entries) {
        List<String> given = new ArrayList<>();
        for (String e : entries == null ? List.<String>of() : entries) {
            if (e != null && !e.isBlank()) {
                given.add(e.strip());
            }
        }
        if (given.isEmpty()) {
            given.add(PUBLIC);
        }
        boolean anywhere = false;
        boolean publicHosts = false;
        java.util.LinkedHashSet<String> origins = new java.util.LinkedHashSet<>();
        for (String e : given) {
            if (e.equals(ANYWHERE)) {
                anywhere = true;
            } else if (e.equalsIgnoreCase(PUBLIC)) {
                publicHosts = true;
            } else {
                origins.add(origin(e));
            }
        }
        return new Destinations(anywhere, publicHosts, java.util.Collections.unmodifiableSet(origins), List.copyOf(given));
    }

    /** {@code entry} as "scheme://host:port", or an {@link IllegalArgumentException} saying why it is not an origin. */
    private static String origin(String entry) {
        URI uri;
        try {
            uri = new URI(entry);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("not a federation destination: '" + entry + "' (" + e.getMessage() + ")");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("a federation destination is 'public', '*' or an http(s) origin such as "
                    + "https://example.org:8443, not '" + entry + "'");
        }
        String authority = uri.getRawAuthority();
        org.apache.http.HttpHost host = target(uri);
        // No '%' either: Apache reads a registry authority decoded, so "localhost%3a-1" was port -1
        // and "a%40localhost" userinfo, past the checks on the raw text (found in co-review round 4
        // of #144). A host name has no use for one; an IPv6 zone id is refused anyway.
        if (host == null || authority == null || authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getRawPath() == null || uri.getRawPath().isEmpty()
                || uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("a federation destination names an origin — scheme, host and port, "
                    + "nothing else — not '" + entry + "'");
        }
        // A port must be one a connection can use: Apache's route planner reads 0 as the scheme's
        // default, so "http://internal.test:0" admitted port 80, which it does not name; 99999 could
        // never match. And a host must be a name or an IP literal: "HTTP://*" read as "any http"
        // admitted only a host named "*" (found in co-review round 2 of #144).
        int port = host.getPort();
        // and the port as written: digits only. Apache reads a registry authority's port with
        // Integer.parseInt, so "sparql_store:-1" was port -1, "no port", and admitted port 80
        // (found in co-review round 3 of #144)
        int colon = authority.lastIndexOf(':');
        if (colon > authority.lastIndexOf(']') && !authority.substring(colon + 1).matches("[0-9]*")) {
            port = 0;   // refused below
        }
        String name = host.getHostName();
        String bare = name.startsWith("[") && name.endsWith("]") ? name.substring(1, name.length() - 1) : name;
        if (port == 0 || port < -1 || port > 65535 || bare.isEmpty()
                || !(bare.indexOf(':') >= 0 ? bare.matches("[0-9A-Fa-f:.]+") : bare.matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))) {
            throw new IllegalArgumentException("a federation destination names a host and, if any, a port from 1 to "
                    + "65535, not '" + entry + "'");
        }
        return key(host);
    }

    /**
     * The host a request to {@code uri} goes to, as Apache's client takes it from the URI
     * ({@code URIUtils.extractHost}), or {@code null} when it names none. Decided on this, not on
     * {@link URI#getHost()}: a name {@code java.net.URI} cannot read as a server ({@code sparql_store},
     * a Docker Compose service) has no {@code getHost()} but is reached all the same, and
     * {@code http://a@b_c@listed.test/} has {@code getHost()} {@code listed.test} once rebuilt, while
     * Apache resolves {@code b_c@listed.test} (both found in co-review round 1 of #144).
     */
    static org.apache.http.HttpHost target(URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            return org.apache.http.client.utils.URIUtils.extractHost(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "scheme://host:port" with the scheme's default port filled in, lower case, IPv6 without brackets. */
    private static String key(org.apache.http.HttpHost host) {
        String s = host.getSchemeName().toLowerCase(Locale.ROOT);
        String h = host.getHostName().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        int p = host.getPort() >= 0 ? host.getPort() : s.equals("https") ? 443 : 80;
        return s + "://" + h + ":" + p;
    }

    /** The entries as given (or {@code public}); for messages and the README's examples. */
    List<String> entries() {
        return entries;
    }

    /**
     * Whether a request to {@code host} may be made, by name: the host name exactly as the client
     * resolves it. A destination that is not http or https is {@link Decision#REFUSED}.
     */
    Decision decide(org.apache.http.HttpHost host) {
        if (host == null || host.getHostName() == null || host.getHostName().isEmpty()
                || !("http".equalsIgnoreCase(host.getSchemeName()) || "https".equalsIgnoreCase(host.getSchemeName()))) {
            return Decision.REFUSED;
        }
        if (anywhere || origins.contains(key(host))) {
            return Decision.ADMITTED;
        }
        return publicHosts ? Decision.IF_PUBLIC : Decision.REFUSED;
    }

    /** As {@link #decide(org.apache.http.HttpHost)}, for {@code scheme://host:port} ({@code port} -1: the scheme's default). */
    Decision decide(String scheme, String host, int port) {
        return host == null || scheme == null ? Decision.REFUSED : decide(new org.apache.http.HttpHost(host, port, scheme));
    }

    /** As {@link #decide(org.apache.http.HttpHost)}, for the host a request to {@code uri} goes to. */
    Decision decide(URI uri) {
        return decide(target(uri));
    }

    /**
     * Why a request to {@code host} may not be made, or {@code null} when it may — by name, and, for
     * a destination admitted only by {@code public}, not through a proxy (which would resolve it
     * where its addresses cannot be checked). The addresses themselves are checked when the
     * connection is opened, by {@link Dns}, for as long as {@link #CHECK} is set.
     */
    String refusal(org.apache.http.HttpHost host) {
        return refusal(host, java.net.ProxySelector.getDefault());
    }

    /**
     * As {@link #refusal(org.apache.http.HttpHost)}, with {@code selector} the proxy selector the
     * client that will make the request routes by — captured when that client was built, as
     * Apache's {@code SystemDefaultRoutePlanner} captures it — so that the check and the route
     * cannot disagree when the JVM's default selector changes later (found in co-review round 4
     * of #144).
     */
    String refusal(org.apache.http.HttpHost host, java.net.ProxySelector selector) {
        if (host == null) {
            return "the IRI names no host";
        }
        Decision d = decide(host);
        if (d == Decision.REFUSED) {
            return "<" + origin(host) + "> is not among the federation destinations (CQELS_SPARQL_FEDERATION_DESTINATIONS: "
                    + String.join(",", entries) + ")";
        }
        if (d == Decision.IF_PUBLIC && proxied(host, selector)) {
            return "<" + origin(host) + "> would be reached through an HTTP proxy, which resolves it where this "
                    + "endpoint cannot check that it is public; list it in CQELS_SPARQL_FEDERATION_DESTINATIONS";
        }
        return null;
    }

    /** As {@link #refusal(org.apache.http.HttpHost)}, for the host a request to {@code uri} goes to. */
    String refusal(URI uri) {
        return refusal(target(uri));
    }

    /** "scheme://host[:port]" of {@code host}, for messages: never a path or query, which may carry data. */
    static String origin(org.apache.http.HttpHost host) {
        return host == null ? "" : host.toURI();
    }

    /** As {@link #origin(org.apache.http.HttpHost)}, for {@code uri}; its scheme alone when it names no host. */
    static String origin(URI uri) {
        org.apache.http.HttpHost host = target(uri);
        return host != null ? origin(host) : uri == null || uri.getScheme() == null ? "" : uri.getScheme() + ":";
    }

    /**
     * Whether {@code selector} routes {@code host} through an HTTP proxy, as Apache's
     * {@code SystemDefaultRoutePlanner} decides it: the first DIRECT or HTTP entry of the selector's
     * answer for {@code new URI(host.toURI())}, which is what that planner asks it.
     */
    static boolean proxied(org.apache.http.HttpHost host, java.net.ProxySelector selector) {
        if (selector == null) {
            return false;
        }
        List<java.net.Proxy> proxies;
        try {
            proxies = selector.select(new URI(host.toURI()));
        } catch (java.net.URISyntaxException e) {
            return true;   // Apache's planner fails on it too; treated as proxied, which only ever refuses
        } catch (RuntimeException e) {
            return true;   // cannot tell: treated as proxied, which only ever refuses
        }
        for (java.net.Proxy p : proxies == null ? List.<java.net.Proxy>of() : proxies) {
            if (p.type() == java.net.Proxy.Type.DIRECT) {
                return false;
            }
            if (p.type() == java.net.Proxy.Type.HTTP) {
                return true;
            }
        }
        return false;
    }

    /**
     * The route planner the outbound clients are built with: the JVM's default proxy selector as it
     * is now, held — the same instance {@link #refusal(org.apache.http.HttpHost, java.net.ProxySelector)}
     * is given for that client's requests. With no default selector, none is used.
     */
    static java.net.ProxySelector currentSelector() {
        java.net.ProxySelector s = java.net.ProxySelector.getDefault();
        return s != null ? s : java.net.ProxySelector.of(null);
    }

    /** Apache's system route planner over {@code selector}, fixed, not re-read from the JVM. */
    static org.apache.http.conn.routing.HttpRoutePlanner routePlanner(java.net.ProxySelector selector) {
        return new org.apache.http.impl.conn.SystemDefaultRoutePlanner(
                org.apache.http.impl.conn.DefaultSchemePortResolver.INSTANCE, selector);
    }

    /**
     * Set, on the thread that makes a request, while that request's destination was admitted only
     * by {@code public}; {@link Dns} then refuses a name any of whose addresses is not public. The
     * caller sets it around one request (one redirect hop) and removes it after.
     */
    static final ThreadLocal<Boolean> CHECK = new ThreadLocal<>();

    /** A name {@link Dns} refused: it resolves to an address that is not public. */
    static final class NotPublic extends UnknownHostException {
        NotPublic(String host) {
            super(host + " is not a public address, and only public addresses may be reached "
                    + "(CQELS_SPARQL_FEDERATION_DESTINATIONS)");
        }
    }

    /**
     * The resolver the outbound clients connect through: the addresses {@code delegate} answers,
     * refused whole when {@link #CHECK} is set and any of them is not public. Every address, not
     * the first: the client tries them in turn, so a name answering one public and one loopback
     * address would reach the loopback one when the public one does not answer.
     */
    static final class Dns implements org.apache.http.conn.DnsResolver {
        private final org.apache.http.conn.DnsResolver delegate;

        Dns(org.apache.http.conn.DnsResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = delegate.resolve(host);
            if (Boolean.TRUE.equals(CHECK.get())) {
                if (addresses == null || addresses.length == 0) {
                    throw new UnknownHostException(host);
                }
                for (InetAddress a : addresses) {
                    if (!isPublic(a)) {
                        throw new NotPublic(host);
                    }
                }
            }
            return addresses;
        }
    }

    /**
     * Whether {@code a} is a public unicast address: not one of IANA's special-purpose ranges
     * (RFC 6890 and its updates) that a request from this server must not reach. IPv6 addresses that
     * carry an IPv4 address (IPv4-mapped, the NAT64 well-known prefix, 6to4) are judged by it; the
     * rest of IPv6 must be global unicast ({@code 2000::/3}) outside its documentation, Teredo and
     * benchmarking ranges. Unique local ({@code fc00::/7}) and site-local addresses are refused.
     */
    static boolean isPublic(InetAddress a) {
        if (a == null || a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return isPublicV4(b, 0);
        }
        if (!(a instanceof Inet6Address) || b.length != 16) {
            return false;
        }
        boolean zero80 = true;
        for (int i = 0; i < 10; i++) {
            zero80 &= b[i] == 0;
        }
        if (zero80 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return isPublicV4(b, 12);   // ::ffff:a.b.c.d, IPv4-mapped
        }
        if (zero80 && b[10] == 0 && b[11] == 0) {
            return false;   // ::/96: unspecified, loopback, and the deprecated IPv4-compatible form
        }
        int w0 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        int w1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        if (w0 == 0x0064 && w1 == 0xff9b) {
            // 64:ff9b::/96 (RFC 6052) translates to the IPv4 address in its last 32 bits;
            // 64:ff9b:1::/48 (RFC 8215) is for local use
            boolean wellKnown = true;
            for (int i = 4; i < 12; i++) {
                wellKnown &= b[i] == 0;
            }
            return wellKnown && isPublicV4(b, 12);
        }
        if ((b[0] & 0xe0) != 0x20) {
            return false;   // outside 2000::/3, global unicast: fc00::/7, fe80::/10, 100::/64 and the rest
        }
        if (w0 == 0x2002) {
            return isPublicV4(b, 2);   // 2002::/16, 6to4: the IPv4 address in bits 16-47
        }
        if (w0 == 0x2001) {
            if (w1 == 0x0000) {
                return false;   // 2001::/32, Teredo: an obfuscated IPv4 address, tunnelled
            }
            if (w1 == 0x0db8) {
                return false;   // 2001:db8::/32, documentation
            }
            if (w1 == 0x0002 && b[4] == 0 && b[5] == 0) {
                return false;   // 2001:2::/48, benchmarking
            }
            if ((w1 & 0xfff0) == 0x0010 || (w1 & 0xfff0) == 0x0020) {
                return false;   // 2001:10::/28 ORCHID and 2001:20::/28 ORCHIDv2: not routable
            }
        }
        if (w0 == 0x3fff && (w1 & 0xf000) == 0) {
            return false;   // 3fff::/20, documentation (RFC 9637)
        }
        return true;
    }

    private static boolean isPublicV4(byte[] b, int at) {
        int o0 = b[at] & 0xff;
        int o1 = b[at + 1] & 0xff;
        int o2 = b[at + 2] & 0xff;
        if (o0 == 0 || o0 == 10 || o0 == 127) {
            return false;   // "this network", private, loopback
        }
        if (o0 == 100 && (o1 & 0xc0) == 64) {
            return false;   // 100.64.0.0/10, shared address space (CGNAT)
        }
        if (o0 == 169 && o1 == 254) {
            return false;   // link-local: cloud metadata services live here
        }
        if (o0 == 172 && (o1 & 0xf0) == 16) {
            return false;   // 172.16.0.0/12, private
        }
        if (o0 == 192 && o1 == 168) {
            return false;   // private
        }
        if (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) {
            return false;   // 192.0.0.0/24 protocol assignments, 192.0.2.0/24 documentation
        }
        if (o0 == 192 && o1 == 88 && o2 == 99) {
            return false;   // 192.88.99.0/24, the deprecated 6to4 relay anycast
        }
        if (o0 == 198 && (o1 & 0xfe) == 18) {
            return false;   // 198.18.0.0/15, benchmarking
        }
        if ((o0 == 198 && o1 == 51 && o2 == 100) || (o0 == 203 && o1 == 0 && o2 == 113)) {
            return false;   // documentation
        }
        return o0 < 224;   // 224.0.0.0/4 multicast, 240.0.0.0/4 reserved, 255.255.255.255 broadcast
    }
}
