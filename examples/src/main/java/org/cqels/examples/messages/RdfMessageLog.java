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
 *       {@code "1.2-messages"} is {@code 1.2-messages}.</li>
 *   <li>{@code MESSAGE}, {@code VERSION}, {@code PREFIX} and {@code BASE} are matched
 *       case-insensitively, as Turtle matches its SPARQL-style directives; the {@code @} forms are
 *       lower-case only.</li>
 *   <li>Not handled: RDF 1.2 triple terms and quoted triples ({@code <<…>>}), which RDF4J 4.3.8's
 *       Turtle and TriG parsers do not read either.</li>
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
     */
    public static String writeNQuads(List<? extends Collection<Statement>> messages) {
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
     * written into the default graph; write such messages with {@link #writeNQuads}.
     *
     * @param namespaces prefix to namespace pairs to declare in every message (may be empty)
     * @throws IllegalArgumentException if a statement has a graph name
     */
    public static String writeTurtle(List<? extends Collection<Statement>> messages,
                                     Map<String, String> namespaces) {
        StringBuilder out = new StringBuilder("VERSION \"").append(VERSION_LABEL).append("\"\n");
        int n = 0;
        for (Collection<Statement> message : messages) {
            n++;
            for (Statement st : message) {
                if (st.getContext() != null) {
                    throw new IllegalArgumentException("message " + n + " has a statement in graph "
                            + st.getContext() + "; Turtle has no named graphs, use writeNQuads");
                }
            }
            if (!message.isEmpty()) {
                Model model = new LinkedHashModel(message);
                namespaces.forEach(model::setNamespace);
                StringWriter turtle = new StringWriter();
                Rio.write(model, turtle, RDFFormat.TURTLE);
                out.append(turtle);
                if (out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
            }
            out.append("MESSAGE\n");
        }
        return out.toString();
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
        /** Characters that end a name token (besides whitespace). */
        private static final String DELIMITERS = "<>\"'{}[](),;#";

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
                if (c == '<') {
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
                } else if (c == '.' && !(i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
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
         * A name-like token: a prefixed name, a keyword, a number, a blank-node label, a language
         * tag or a datatype marker. It runs to whitespace or a delimiter character; a backslash
         * escapes the next character (escaped local names such as {@code ex:a\.b}); trailing dots
         * are not part of it, because a name cannot end in '.', so they are left to end the
         * statement.
         */
        private String readWord() {
            if (startsNumber()) {
                return readNumber();
            }
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i = Math.min(s.length(), i + 2);
                } else if (Character.isWhitespace(c) || DELIMITERS.indexOf(c) >= 0) {
                    break;
                } else {
                    i++;
                }
            }
            int end = i;
            while (end > start + 1 && s.charAt(end - 1) == '.' && s.charAt(end - 2) != '\\') {
                end--;
            }
            if (end == start) {                 // a lone character no other branch took
                end = Math.min(s.length(), start + 1);
            }
            i = end;
            return s.substring(start, end);
        }

        /** A digit, or a sign or '.' directly followed by one. */
        private boolean startsNumber() {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                return true;
            }
            int k = i;
            if (c == '+' || c == '-') {
                k++;
            }
            if (k < s.length() && s.charAt(k) == '.') {
                k++;
            }
            return k > i && k < s.length() && Character.isDigit(s.charAt(k));
        }

        /**
         * A Turtle number, by its grammar: {@code [+-]? [0-9]* ('.' [0-9]+)? ([eE] [+-]? [0-9]+)?}.
         * A '.' not followed by a digit is not part of it, so {@code 1.MESSAGE} is the integer 1,
         * a statement terminator, and a delimiter.
         */
        private String readNumber() {
            int start = i;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (i + 1 < s.length() && s.charAt(i) == '.' && Character.isDigit(s.charAt(i + 1))) {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int k = i + 1;
                if (k < s.length() && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                    k++;
                }
                if (k < s.length() && Character.isDigit(s.charAt(k))) {
                    i = k;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
            }
            return s.substring(start, i);
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
            body.append(directive).append('\n');
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

        /** The value of a quoted Turtle string: quotes removed, escapes decoded. */
        private static String decode(String quoted) {
            int q = quoted.length() >= 6 && (quoted.startsWith("\"\"\"") || quoted.startsWith("'''")) ? 3 : 1;
            String inner = quoted.substring(q, quoted.length() - q);
            StringBuilder out = new StringBuilder();
            for (int k = 0; k < inner.length(); k++) {
                char c = inner.charAt(k);
                if (c != '\\' || k + 1 >= inner.length()) {
                    out.append(c);
                    continue;
                }
                char e = inner.charAt(++k);
                switch (e) {
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 'f' -> out.append('\f');
                    case 'u', 'U' -> {
                        int len = e == 'u' ? 4 : 8;
                        if (k + len >= inner.length()) {
                            throw new IllegalArgumentException("truncated \\" + e + " escape in " + quoted);
                        }
                        out.appendCodePoint(Integer.parseInt(inner.substring(k + 1, k + 1 + len), 16));
                        k += len;
                    }
                    default -> out.append(e);          // \" \' \\
                }
            }
            return out.toString();
        }
    }
}
