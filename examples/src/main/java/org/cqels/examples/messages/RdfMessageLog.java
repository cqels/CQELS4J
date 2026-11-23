package org.cqels.examples.messages;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.cqels.stream.codec.RdfMessageCodec;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * Reads and writes <strong>RDF Message Logs</strong> as defined by the W3C RSP Community Group's
 * <a href="https://w3c-cg.github.io/rsp/spec/messages">RDF Messages</a> draft (Living Document,
 * 22 October 2026, §2.1): a file of RDF Messages separated by {@code MESSAGE} delimiters, announced
 * by a version label with the {@code -messages} suffix.
 *
 * <p>Each message comes back as one {@code List<Statement>}, which is exactly one atomic CQELS
 * stream element ({@code DataStream.push(Collection, long)}).
 *
 * <p><strong>What is the engine's and what is this class's.</strong>
 * <ul>
 *   <li><em>N-Triples and N-Quads</em> are the engine's: {@link RdfMessageCodec#parseNQuads} and
 *       {@link RdfMessageCodec#writeNQuads} (cqels-core). This class adds workarounds for three
 *       codec gaps, all tracked in <a href="https://github.com/cqels/CQELS4J/issues/126">#126</a>:
 *       before parsing it removes a comment that follows {@code MESSAGE} on the same line (the
 *       codec rejects it) and every comment-only line (the codec opens an empty message for one
 *       after the last delimiter); and when the last message is empty it writes one more
 *       delimiter, which the codec's writer omits, so that the empty message survives
 *       re-reading.</li>
 *   <li><em>Turtle and TriG</em> are this class's. RDF4J 4.3.8, the version the engine ships,
 *       rejects both {@code VERSION} and {@code MESSAGE}, so the log is tokenised here and each
 *       message is handed to RDF4J's ordinary Turtle or TriG parser.</li>
 * </ul>
 *
 * <p>For the other log formats of the draft, see {@link JsonLdMessageLog} (NDJSON-LD, YAML-LD) and
 * {@link RdfXmlMessageLog} (RDF/XML).
 *
 * <p><strong>Rules implemented</strong> (§2.1.1 to §2.1.3), the same for every format:
 * <ul>
 *   <li>A delimiter ends the current message. A message is opened by a triple, a quad, or another
 *       delimiter, so a delimiter before any statement makes the first message empty, and a
 *       trailing delimiter followed by the end of the file adds nothing.</li>
 *   <li>Blank nodes are scoped to their message: each message is parsed on its own, so the same
 *       {@code _:b} label in two messages gives two different blank nodes.</li>
 *   <li>{@code PREFIX} and {@code BASE} (and {@code @prefix}, {@code @base}) carry over into later
 *       messages; a repeated one overrides the earlier one from that point on.</li>
 * </ul>
 *
 * <p><strong>Turtle/TriG specifics.</strong>
 * <ul>
 *   <li>A delimiter is recognised only as a whole token where a statement may start: never inside
 *       a string, an IRI, a comment, a prefixed name ({@code ex:MESSAGE}, {@code ex:x..MESSAGE},
 *       {@code MESSAGE.x:y}, escaped local names), a {@code [ ]}, {@code ( )} or graph block.</li>
 *   <li>Comments may stand wherever whitespace may, including inside directives.</li>
 *   <li>Strict about the version label: a delimiter is accepted only while the version label most
 *       recently announced ends in {@code -messages}; a delimiter with no such label in force is
 *       rejected. (The engine's N-Quads codec accepts one; see #126.) A label is decoded, so
 *       a hyphen written as the escape backslash-u002D counts as a hyphen; it must be a short
 *       quoted string with valid escapes (Turtle's VersionSpecifier).</li>
 *   <li>{@code MESSAGE}, {@code VERSION}, {@code PREFIX} and {@code BASE} are matched
 *       case-insensitively, as Turtle matches its SPARQL-style directives; the {@code @} forms are
 *       lower-case only.</li>
 *   <li>RDF 1.2 syntax that RDF4J 4.3.8 cannot read, or would read as different data, is refused
 *       with an error naming <a href="https://github.com/cqels/CQELS4J/issues/133">#133</a>:
 *       triple terms {@code <<( s p o )>>}, reified triples {@code << s p o >>} (RDF4J reads them
 *       as RDF-star quoted triples), annotations {@code {| |}}, and base direction
 *       {@code "x"@ar--rtl} (RDF4J reads {@code ar--rtl} as the language tag).</li>
 * </ul>
 */
public final class RdfMessageLog {

    private RdfMessageLog() {
    }

    /** The version label this class writes. */
    public static final String VERSION_LABEL = "1.2-messages";

    /** {@code MESSAGE} followed, with or without a space, by a comment on the same line (#126). */
    private static final Pattern MESSAGE_WITH_COMMENT =
            Pattern.compile("(?m)^([ \\t]*MESSAGE)[ \\t]*#[^\\r\\n]*$");

    /** A line holding only a comment. In N-Triples/N-Quads no statement can start with '#'. */
    private static final Pattern COMMENT_ONLY_LINE = Pattern.compile("(?m)^[ \\t]*#[^\\r\\n]*$");

    // ------------------------------------------------------------------------------------ reading

    /**
     * Parse an RDF Message Log.
     *
     * @param log     the whole log
     * @param format  N-Triples, N-Quads, Turtle or TriG
     * @param baseIri base IRI for relative IRIs (Turtle/TriG; ignored for N-Triples/N-Quads)
     * @return one statement list per message, in order; an empty message is an empty list
     * @throws IllegalArgumentException if the log is malformed
     */
    public static List<List<Statement>> parse(String log, RDFFormat format, String baseIri) {
        if (format.equals(RDFFormat.NTRIPLES) || format.equals(RDFFormat.NQUADS)) {
            String cleaned = MESSAGE_WITH_COMMENT.matcher(log).replaceAll("$1");
            cleaned = COMMENT_ONLY_LINE.matcher(cleaned).replaceAll("");
            return RdfMessageCodec.parseNQuads(cleaned);
        }
        if (format.equals(RDFFormat.TURTLE) || format.equals(RDFFormat.TRIG)) {
            return parseTurtleFamily(log, format, baseIri);
        }
        throw new IllegalArgumentException("unsupported RDF Message Log format: " + format.getName());
    }

    private static List<List<Statement>> parseTurtleFamily(String log, RDFFormat format, String baseIri) {
        List<Segment> segments = new Splitter(log).split();
        List<List<Statement>> messages = new ArrayList<>();
        int last = segments.size() - 1;
        for (int i = 0; i <= last; i++) {
            List<Statement> statements = parseSegment(segments.get(i), format, baseIri, i);
            // A segment followed by a delimiter is a message even when empty; the last segment is a
            // message only if it holds a statement (a trailing delimiter adds nothing).
            if (i < last || !statements.isEmpty()) {
                messages.add(statements);
            }
        }
        return messages;
    }

    private static List<Statement> parseSegment(Segment segment, RDFFormat format, String baseIri, int index) {
        // RDF4J reads "1." as an integer and its terminator only if something follows the dot
        String text = segment.header() + segment.body() + "\n";
        if (text.isBlank()) {
            return List.of();
        }
        try {
            Model model = Rio.parse(new StringReader(text), baseIri, format);
            return new ArrayList<>(model);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("message " + (index + 1) + " does not parse as "
                    + format.getName() + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Write an N-Quads RDF Message Log with the engine's writer, adding a final delimiter when the
     * last message is empty so that it survives re-reading (#126). Named graphs are kept.
     *
     * @throws IllegalArgumentException if a statement holds a triple term (#133)
     */
    public static String writeNQuads(List<? extends Collection<Statement>> messages) {
        checkWritable(messages, true, "N-Quads");
        String log = RdfMessageCodec.writeNQuads(messages);
        if (!messages.isEmpty() && messages.get(messages.size() - 1).isEmpty()) {
            log = (log.endsWith("\n") ? log : log + "\n") + "MESSAGE\n";
        }
        return log;
    }

    /**
     * Write a Turtle RDF Message Log: the version announcement, then each message as an ordinary
     * Turtle document, each one followed by {@code MESSAGE}. Terminating every message (rather
     * than separating them) keeps an empty last message (§2.1.2). Each message carries its own
     * {@code @prefix} lines, which §2.1.3 allows.
     *
     * <p>Turtle has no named graphs, so a statement with a graph name is refused rather than
     * written into the default graph; write such messages with {@link #writeTriG} or
     * {@link #writeNQuads}.
     *
     * @param namespaces prefix to namespace pairs to declare in every message (may be empty)
     * @throws IllegalArgumentException if a statement has a graph name, or holds a triple term
     *                                  (RDF4J 4.3.8 would write it as RDF-star, #133)
     */
    public static String writeTurtle(List<? extends Collection<Statement>> messages,
                                     Map<String, String> namespaces) {
        checkWritable(messages, false, "Turtle");
        return write(messages, namespaces, RDFFormat.TURTLE);
    }

    /**
     * Write a TriG RDF Message Log, as {@link #writeTurtle} does, keeping named graphs.
     *
     * @throws IllegalArgumentException if a statement holds a triple term (#133)
     */
    public static String writeTriG(List<? extends Collection<Statement>> messages,
                                   Map<String, String> namespaces) {
        checkWritable(messages, true, "TriG");
        return write(messages, namespaces, RDFFormat.TRIG);
    }

    private static String write(List<? extends Collection<Statement>> messages,
                                Map<String, String> namespaces, RDFFormat format) {
        StringBuilder out = new StringBuilder("VERSION \"").append(VERSION_LABEL).append("\"\n");
        for (Collection<Statement> message : messages) {
            if (!message.isEmpty()) {
                Model model = new LinkedHashModel(message);
                namespaces.forEach(model::setNamespace);
                StringWriter text = new StringWriter();
                Rio.write(model, text, format);
                out.append(text);
                if (out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
            }
            out.append("MESSAGE\n");
        }
        return out.toString();
    }

    /**
     * Refuse what a writer cannot write faithfully: a triple term (RDF4J 4.3.8 writes RDF-star,
     * which RDF 1.2 reads differently and which the readers here refuse, #133), and a graph name
     * where the format has no named graphs.
     */
    static void checkWritable(List<? extends Collection<Statement>> messages, boolean graphs, String format) {
        int n = 0;
        for (Collection<Statement> message : messages) {
            n++;
            for (Statement st : message) {
                if (!graphs && st.getContext() != null) {
                    throw new IllegalArgumentException("message " + n + " has a statement in graph "
                            + st.getContext() + "; " + format + " has no named graphs");
                }
                if (st.getSubject() instanceof Triple || st.getObject() instanceof Triple) {
                    throw new IllegalArgumentException("message " + n + " holds a triple term, which"
                            + " cannot be written faithfully with RDF4J 4.3.8 (#133)");
                }
            }
        }
    }

    // ---------------------------------------------------------------- Turtle/TriG log tokeniser

    /** One message's text, plus the PREFIX/BASE directives in force where it starts. */
    private record Segment(String header, String body) {
    }

    /**
     * Tokenises a Turtle/TriG log once and cuts it at every {@code MESSAGE} / {@code @message .}
     * that stands, as a whole token, where a statement may start. Strings, IRIs and names are read
     * as whole tokens, so nothing inside them can look like a delimiter; comments are dropped.
     * {@code VERSION} / {@code @version} is removed (RDF4J 4.3.8 does not accept it) and its decoded
     * label tracked. PREFIX/BASE directives are rewritten without their comments, kept in place,
     * and accumulated, so every later message is parsed with the directives in force where it
     * starts.
     */
    private static final class Splitter {
        private final String s;
        private int i;
        private int depth;                 // nesting of {}, [] and ()
        private boolean statementStart = true;
        private String versionLabel;       // most recent VERSION label, decoded
        private final StringBuilder header = new StringBuilder();
        private String segmentHeader = "";
        private StringBuilder body = new StringBuilder();
        private final List<Segment> segments = new ArrayList<>();

        Splitter(String s) {
            this.s = s;
        }

        List<Segment> split() {
            while (true) {
                skipSpace(true);
                if (i >= s.length()) {
                    break;
                }
                char c = s.charAt(i);
                if (c == '<' && i + 1 < s.length() && s.charAt(i + 1) == '<') {
                    throw new IllegalArgumentException("'<<' at offset " + i + ": RDF 1.2 triple"
                            + " terms and reified triples are refused, because RDF4J 4.3.8 cannot"
                            + " parse <<( )>> and reads << >> as an RDF-star quoted triple, which"
                            + " RDF 1.2 does not (#133)");
                } else if (c == '{' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                    throw new IllegalArgumentException("'{|' at offset " + i + ": RDF 1.2"
                            + " annotations are refused, because RDF4J 4.3.8 reads them with RDF-star"
                            + " semantics, not RDF 1.2's (#133)");
                } else if (c == '<') {
                    body.append(readIri());
                    statementStart = false;
                } else if (c == '"' || c == '\'') {
                    body.append(readString());
                    statementStart = false;
                } else if (c == '{' || c == '[' || c == '(') {
                    depth++;
                    body.append(c);
                    i++;
                    statementStart = c == '{';     // a graph block's content starts statements
                } else if (c == '}' || c == ']' || c == ')') {
                    depth = Math.max(0, depth - 1);
                    body.append(c);
                    i++;
                    // a TriG graph block closed at the top level ends a statement without '.'
                    statementStart = c == '}' && depth == 0;
                } else if (c == ',' || c == ';') {
                    body.append(c);
                    i++;
                    statementStart = false;
                } else if (c == '.' && !digitAt(i + 1)) {
                    body.append(c);                // a statement terminator
                    i++;
                    statementStart = true;
                } else {
                    String word = readWord();
                    if (depth == 0 && statementStart && directive(word)) {
                        statementStart = true;
                    } else {
                        body.append(word);
                        statementStart = false;
                    }
                }
            }
            segments.add(new Segment(segmentHeader, body.toString()));
            return segments;
        }

        /** Skip whitespace and comments; whitespace is copied when {@code copy}, comments never. */
        private void skipSpace(boolean copy) {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '#') {
                    while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
                        i++;
                    }
                } else if (Character.isWhitespace(c)) {
                    if (copy) {
                        body.append(c);
                    }
                    i++;
                } else {
                    return;
                }
            }
        }

        /**
         * A token that is not a string, IRI or punctuation, read by the Turtle grammar's own rules
         * so that a following '.' or delimiter is never swallowed:
         * <ul>
         *   <li>a language tag or {@code @}-directive: {@code @[a-zA-Z]+(-[a-zA-Z0-9]+)*}; an
         *       RDF 1.2 direction ({@code --ltr} / {@code --rtl}) is refused (#133);</li>
         *   <li>the datatype marker {@code ^^};</li>
         *   <li>a number ({@link #readNumber});</li>
         *   <li>otherwise a name: a prefixed name, a blank-node label or a keyword
         *       ({@link #readName}).</li>
         * </ul>
         */
        private String readWord() {
            char c = s.charAt(i);
            if (c == '@') {
                return readAtWord();
            }
            if (c == '^' && i + 1 < s.length() && s.charAt(i + 1) == '^') {
                i += 2;
                return "^^";
            }
            if (startsNumber()) {
                return readNumber();
            }
            return readName();
        }

        private String readAtWord() {
            int start = i++;
            while (i < s.length() && isAsciiLetter(s.charAt(i))) {
                i++;
            }
            while (i + 1 < s.length() && s.charAt(i) == '-' && isAsciiLetterOrDigit(s.charAt(i + 1))) {
                i++;
                while (i < s.length() && isAsciiLetterOrDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i + 2 < s.length() && s.startsWith("--", i) && isAsciiLetter(s.charAt(i + 2))) {
                throw new IllegalArgumentException("'" + s.substring(start, i) + "--' at offset "
                        + start + ": RDF 1.2 base direction is refused, because RDF4J 4.3.8 reads"
                        + " it as part of the language tag (#133)");
            }
            return s.substring(start, i);
        }

        /**
         * A prefixed name, blank-node label or keyword. Name characters are ASCII letters and
         * digits, {@code _ - :}, any non-ASCII character that is not whitespace (Turtle's
         * PN_CHARS_BASE is mostly non-ASCII), {@code %xx} and a backslash escape. A '.' belongs to
         * the name only if a name character follows the run of dots and the dot does not open a
         * local name, and only in a name that has a ':' (a keyword or directive has no dots): so
         * {@code ex:x..MESSAGE}, {@code MESSAGE.foo:s} and {@code _:b.1} are one token, while in
         * {@code true.MESSAGE}, {@code true.@message}, {@code ex:.MESSAGE} and {@code "x"^^ex:.}
         * the dot ends the statement.
         */
        private String readName() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    i += 2;
                } else if (c == '%') {
                    i = Math.min(s.length(), i + 3);
                } else if (isNameChar(c)) {
                    i++;
                } else if (c == '.' && i > start && i - 1 != start + s.substring(start, i).indexOf(':')) {
                    // (a dot may not open the local name, i.e. follow the prefix's ':' directly; a
                    // later ':' inside the local name, as in ex:x:.y, does not count)
                    int k = i;
                    while (k < s.length() && s.charAt(k) == '.') {
                        k++;
                    }
                    boolean nameFollows = k < s.length()
                            && (isNameChar(s.charAt(k)) || s.charAt(k) == '\\' || s.charAt(k) == '%');
                    // A name without ':' is a keyword (a, true, false) or directive, which has no
                    // dots; a dot before the ':' belongs to a prefix such as MESSAGE.foo:
                    boolean prefixed = s.substring(start, i).indexOf(':') >= 0 || colonAhead(k);
                    if (nameFollows && prefixed) {
                        i = k;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            if (i == start) {                       // a character no rule takes: pass it through
                i++;
            }
            return s.substring(start, i);
        }

        /** Whether a ':' comes before the run of name characters and dots from {@code k} ends. */
        private boolean colonAhead(int k) {
            while (k < s.length() && (isNameChar(s.charAt(k)) || s.charAt(k) == '.')) {
                if (s.charAt(k) == ':') {
                    return true;
                }
                k++;
            }
            return false;
        }

        private static boolean isNameChar(char c) {
            return isAsciiLetterOrDigit(c) || c == '_' || c == '-' || c == ':'
                    || (c > 0x7F && !Character.isWhitespace(c));
        }

        private static boolean isAsciiDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isAsciiLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        private static boolean isAsciiLetterOrDigit(char c) {
            return isAsciiLetter(c) || isAsciiDigit(c);
        }

        private boolean digitAt(int k) {
            return k < s.length() && isAsciiDigit(s.charAt(k));
        }

        /** An ASCII digit, or a sign and/or '.' directly followed by one. */
        private boolean startsNumber() {
            int k = i;
            if (s.charAt(k) == '+' || s.charAt(k) == '-') {
                k++;
            }
            if (k < s.length() && s.charAt(k) == '.') {
                k++;
            }
            return digitAt(k);
        }

        /**
         * A Turtle number by its grammar: INTEGER {@code [+-]?[0-9]+}, DECIMAL
         * {@code [+-]?[0-9]*'.'[0-9]+}, DOUBLE {@code [+-]?([0-9]+'.'[0-9]*EXP | '.'?[0-9]+EXP)}.
         * A '.' is part of it only when a digit, or (after digits) an exponent, follows; so
         * {@code 1.MESSAGE} is the integer 1, a terminator and a delimiter, and {@code 1.e2} is one
         * DOUBLE.
         */
        private String readNumber() {
            int start = i;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') {
                i++;
            }
            int digits = 0;
            while (digitAt(i)) {
                i++;
                digits++;
            }
            if (i < s.length() && s.charAt(i) == '.') {
                if (digitAt(i + 1)) {
                    i++;
                    while (digitAt(i)) {
                        i++;
                    }
                } else if (digits > 0 && exponentAt(i + 1) > 0) {
                    i++;                            // 1.e2
                }
            }
            int exp = exponentAt(i);
            if (exp > 0) {
                i += exp;
            }
            return s.substring(start, i);
        }

        /** The length of an exponent {@code [eE][+-]?[0-9]+} starting at {@code k}, or 0. */
        private int exponentAt(int k) {
            if (k >= s.length() || (s.charAt(k) != 'e' && s.charAt(k) != 'E')) {
                return 0;
            }
            int j = k + 1;
            if (j < s.length() && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            if (!digitAt(j)) {
                return 0;
            }
            while (digitAt(j)) {
                j++;
            }
            return j - k;
        }

        private String readIri() {
            int start = i;
            i++;
            while (i < s.length() && s.charAt(i) != '>') {
                char c = s.charAt(i);
                if (c == '\n' || c == '\r') {
                    throw new IllegalArgumentException("unterminated IRI at offset " + start);
                }
                i++;
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("unterminated IRI at offset " + start);
            }
            i++;
            return s.substring(start, i);
        }

        private String readString() {
            int start = i;
            char quote = s.charAt(i);
            String triple = String.valueOf(quote).repeat(3);
            boolean longString = s.startsWith(triple, i);
            i += longString ? 3 : 1;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i += 2;
                } else if (longString && s.startsWith(triple, i)) {
                    // the first unescaped triple quote closes it: Turtle's grammar does not let a
                    // long string's content end in a quote
                    i += 3;
                    return s.substring(start, i);
                } else if (!longString && c == quote) {
                    i++;
                    return s.substring(start, i);
                } else if (!longString && (c == '\n' || c == '\r')) {
                    break;
                } else {
                    i++;
                }
            }
            throw new IllegalArgumentException("unterminated string at offset " + start);
        }

        /** Handle {@code word} as a directive if it is one; return false if it is not. */
        private boolean directive(String word) {
            String upper = word.toUpperCase(Locale.ROOT);
            boolean at = word.startsWith("@");
            if (at && !word.equals(word.toLowerCase(Locale.ROOT))) {
                return false;                       // @-directives are lower-case only
            }
            switch (upper) {
                case "MESSAGE", "@MESSAGE" -> {
                    if (at) {
                        expectDot(word);
                    }
                    cut();
                    return true;
                }
                case "VERSION", "@VERSION" -> {
                    skipSpace(false);
                    if (i >= s.length() || (s.charAt(i) != '"' && s.charAt(i) != '\'')) {
                        throw new IllegalArgumentException(word + " must be followed by a quoted label");
                    }
                    versionLabel = decode(readString());
                    if (at) {
                        expectDot(word);
                    }
                    return true;
                }
                case "PREFIX", "@PREFIX" -> {
                    skipSpace(false);
                    String ns = readWord();
                    if (!ns.endsWith(":")) {
                        throw new IllegalArgumentException(word + " needs a prefix ending in ':', found '" + ns + "'");
                    }
                    skipSpace(false);
                    String iri = expectIri(word);
                    if (at) {
                        expectDot(word);
                    }
                    keep((at ? "@prefix " : "PREFIX ") + ns + " " + iri + (at ? " ." : ""));
                    return true;
                }
                case "BASE", "@BASE" -> {
                    skipSpace(false);
                    String iri = expectIri(word);
                    if (at) {
                        expectDot(word);
                    }
                    keep((at ? "@base " : "BASE ") + iri + (at ? " ." : ""));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private String expectIri(String directive) {
            if (i >= s.length() || s.charAt(i) != '<') {
                throw new IllegalArgumentException(directive + " must be followed by an IRI");
            }
            return readIri();
        }

        private void expectDot(String directive) {
            skipSpace(false);
            if (i >= s.length() || s.charAt(i) != '.') {
                throw new IllegalArgumentException(directive + " must end with '.'");
            }
            i++;
        }

        /** A PREFIX/BASE directive: into this message's text and the header carried forward. */
        private void keep(String directive) {
            // on a line of its own: RDF4J does not read a directive glued to the previous '.'
            body.append('\n').append(directive).append('\n');
            header.append(directive).append('\n');
        }

        /** End the current message at a delimiter, if the version label in force allows one. */
        private void cut() {
            if (versionLabel == null || !versionLabel.endsWith("-messages")) {
                throw new IllegalArgumentException("MESSAGE delimiter while the version label is "
                        + (versionLabel == null ? "not announced" : "\"" + versionLabel + "\"")
                        + "; an RDF Message Log announces a label ending in -messages (§2.1.1)");
            }
            segments.add(new Segment(segmentHeader, body.toString()));
            segmentHeader = header.toString();
            body = new StringBuilder();
        }

        /**
         * The value of a version label, which Turtle's grammar (VersionSpecifier) allows only as a
         * short single- or double-quoted string: quotes removed, escapes decoded. An escape outside
         * ECHAR (backslash followed by one of {@code t b n r f " ' \\}) or UCHAR (backslash-u with
         * exactly 4, backslash-U with exactly 8 hexadecimal digits, naming a Unicode scalar value) is
         * an error.
         */
        private static String decode(String quoted) {
            if (quoted.startsWith("\"\"\"") || quoted.startsWith("'''")) {
                throw new IllegalArgumentException("a version label must be a short quoted string: " + quoted);
            }
            String inner = quoted.substring(1, quoted.length() - 1);
            StringBuilder out = new StringBuilder();
            for (int k = 0; k < inner.length(); k++) {
                char c = inner.charAt(k);
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (k + 1 >= inner.length()) {
                    throw new IllegalArgumentException("dangling backslash in version label " + quoted);
                }
                char e = inner.charAt(++k);
                switch (e) {
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 'f' -> out.append('\f');
                    case '"', '\'', '\\' -> out.append(e);
                    case 'u', 'U' -> {
                        int len = e == 'u' ? 4 : 8;
                        if (k + len >= inner.length()) {
                            throw new IllegalArgumentException("truncated \\" + e + " escape in " + quoted);
                        }
                        String hex = inner.substring(k + 1, k + 1 + len);
                        if (!hex.chars().allMatch(h -> (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f')
                                || (h >= 'A' && h <= 'F'))) {
                            throw new IllegalArgumentException("bad \\" + e + " escape in " + quoted);
                        }
                        int cp = Integer.parseInt(hex, 16);
                        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                            throw new IllegalArgumentException("\\" + e + hex + " is not a Unicode scalar value");
                        }
                        out.appendCodePoint(cp);
                        k += len;
                    }
                    default -> throw new IllegalArgumentException("invalid escape \\" + e + " in version label " + quoted);
                }
            }
            return out.toString();
        }
    }
}
