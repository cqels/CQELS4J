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
 *       {@link RdfMessageCodec#writeNQuads} (cqels-core). This class adds two workarounds, each
 *       tracked in <a href="https://github.com/cqels/CQELS4J/issues/126">#126</a>: it strips a
 *       comment that follows {@code MESSAGE} on the same line, which the codec rejects; and when
 *       the last message is empty it writes one more delimiter, which the codec's writer omits so
 *       that the empty message is lost on re-reading.</li>
 *   <li><em>Turtle and TriG</em> are this class's. RDF4J 4.3.8, the version the engine ships,
 *       rejects both {@code VERSION} and {@code MESSAGE}, so the log is split here and each
 *       message is handed to RDF4J's ordinary Turtle or TriG parser.</li>
 * </ul>
 *
 * <p><strong>Rules implemented</strong> (§2.1.1–§2.1.3), the same for every format:
 * <ul>
 *   <li>A delimiter ends the current message. A message is opened by a triple, a quad, or another
 *       delimiter, so a delimiter before any statement makes the first message empty, and a
 *       trailing delimiter followed by the end of the file adds nothing.</li>
 *   <li>Blank nodes are scoped to their message: each message is parsed on its own, so the same
 *       {@code _:b} label in two messages gives two different blank nodes.</li>
 *   <li>{@code PREFIX} and {@code BASE} (and {@code @prefix}, {@code @base}) carry over into later
 *       messages; a repeated one overrides the earlier one from that point on.</li>
 *   <li>The Turtle/TriG reader is strict: a delimiter in a document whose version label lacks the
 *       {@code -messages} suffix, or that has no version label, is rejected. (The engine's N-Quads
 *       codec accepts such a document; see #126.)</li>
 *   <li>{@code MESSAGE} and {@code @message .} are both delimiters in Turtle/TriG; {@code MESSAGE},
 *       like {@code PREFIX}, is matched case-insensitively.</li>
 * </ul>
 */
public final class RdfMessageLog {

    private RdfMessageLog() {
    }

    /** The version label this class writes. */
    public static final String VERSION_LABEL = "1.2-messages";

    /** {@code MESSAGE} followed by a comment on the same line (the codec rejects it, #126). */
    private static final Pattern MESSAGE_WITH_COMMENT =
            Pattern.compile("(?m)^([ \\t]*MESSAGE)[ \\t]+#[^\\r\\n]*$");

    // ------------------------------------------------------------------------------------ reading

    /**
     * Parse an RDF Message Log.
     *
     * @param log     the whole log
     * @param format  N-Triples, N-Quads, Turtle or TriG
     * @param baseIri base IRI for relative IRIs (Turtle/TriG; ignored for N-Triples/N-Quads)
     * @return one statement list per message, in order; an empty message is an empty list
     */
    public static List<List<Statement>> parse(String log, RDFFormat format, String baseIri) {
        if (format.equals(RDFFormat.NTRIPLES) || format.equals(RDFFormat.NQUADS)) {
            return RdfMessageCodec.parseNQuads(MESSAGE_WITH_COMMENT.matcher(log).replaceAll("$1"));
        }
        if (format.equals(RDFFormat.TURTLE) || format.equals(RDFFormat.TRIG)) {
            return parseTurtleFamily(log, format, baseIri);
        }
        throw new IllegalArgumentException("unsupported RDF Message Log format: " + format.getName());
    }

    private static List<List<Statement>> parseTurtleFamily(String log, RDFFormat format, String baseIri) {
        Split split = new Splitter(log).split();
        if (split.delimiters > 0 && (split.versionLabel == null || !split.versionLabel.endsWith("-messages"))) {
            throw new IllegalArgumentException("MESSAGE delimiter in a document whose version label is "
                    + (split.versionLabel == null ? "missing" : "\"" + split.versionLabel + "\"")
                    + "; RDF Message Logs announce a label ending in -messages (§2.1.1)");
        }
        List<List<Statement>> messages = new ArrayList<>();
        int last = split.segments.size() - 1;
        for (int i = 0; i <= last; i++) {
            Segment segment = split.segments.get(i);
            List<Statement> statements = parseSegment(segment, format, baseIri, i);
            // A segment followed by a delimiter is a message even when empty; the last segment is a
            // message only if it holds a statement (a trailing delimiter adds nothing).
            if (i < last || !statements.isEmpty()) {
                messages.add(statements);
            }
        }
        return messages;
    }

    private static List<Statement> parseSegment(Segment segment, RDFFormat format, String baseIri, int index) {
        String text = segment.header + segment.body;
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
     * last message is empty so that it survives re-reading (#126).
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
     * @param namespaces prefix → namespace pairs to declare in every message (may be empty)
     */
    public static String writeTurtle(List<? extends Collection<Statement>> messages,
                                     Map<String, String> namespaces) {
        StringBuilder out = new StringBuilder("VERSION \"").append(VERSION_LABEL).append("\"\n");
        for (Collection<Statement> message : messages) {
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

    // ------------------------------------------------------------------- Turtle/TriG log splitter

    /** One message's text, plus the PREFIX/BASE directives in force where it starts. */
    private record Segment(String header, String body) {
    }

    private record Split(List<Segment> segments, int delimiters, String versionLabel) {
    }

    /**
     * Walks a Turtle/TriG log once, tracking strings, IRIs, comments and nesting, and cuts it at
     * every {@code MESSAGE} / {@code @message .} that stands where a statement could start. The
     * {@code VERSION} / {@code @version} announcement is removed from the text (RDF4J 4.3.8 does
     * not accept it) and its label recorded. PREFIX/BASE directives are kept in place and also
     * accumulated, so every later message is parsed with the directives in force where it starts.
     */
    private static final class Splitter {
        private final String s;
        private int i;
        private int depth;                 // nesting of {}, [] and ()
        private boolean statementStart = true;
        private final StringBuilder header = new StringBuilder();   // directives seen so far
        private String segmentHeader = "";
        private StringBuilder body = new StringBuilder();
        private final List<Segment> segments = new ArrayList<>();
        private int delimiters;
        private String versionLabel;

        Splitter(String s) {
            this.s = s;
        }

        Split split() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '#') {
                    skipComment();
                } else if (c == '"' || c == '\'') {
                    copyString(c);
                    statementStart = false;
                } else if (c == '<') {
                    copyIri();
                    statementStart = false;
                } else if (Character.isWhitespace(c)) {
                    body.append(c);
                    i++;
                } else if (depth == 0 && statementStart && startsDirective()) {
                    // handled inside startsDirective()
                } else {
                    copyOrdinary(c);
                }
            }
            segments.add(new Segment(segmentHeader, body.toString()));
            return new Split(segments, delimiters, versionLabel);
        }

        private void copyOrdinary(char c) {
            body.append(c);
            i++;
            switch (c) {
                case '{', '[', '(' -> {
                    depth++;
                    statementStart = c == '{';          // a graph block's content starts statements
                }
                case '}', ']', ')' -> {
                    depth = Math.max(0, depth - 1);
                    // a TriG graph block closed at the top level ends a statement without '.'
                    statementStart = c == '}' && depth == 0;
                }
                case '.' -> statementStart = !insideName();
                default -> statementStart = false;
            }
        }

        /**
         * A '.' between two name characters is part of a token (the decimal point in 0.5, or a dot
         * inside a prefixed name such as {@code ex:a.b}), not the end of a statement.
         */
        private boolean insideName() {
            int before = i - 2;
            return before >= 0 && isNameChar(s.charAt(before)) && i < s.length() && isNameChar(s.charAt(i));
        }

        private static boolean isNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '%';
        }

        private void skipComment() {
            while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
                i++;
            }
        }

        private void copyIri() {
            int start = i;
            i++;
            while (i < s.length() && s.charAt(i) != '>') {
                i++;
            }
            i = Math.min(s.length(), i + 1);
            body.append(s, start, i);
        }

        private void copyString(char quote) {
            int start = i;
            boolean longString = s.startsWith(String.valueOf(quote).repeat(3), i);
            i += longString ? 3 : 1;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i += 2;
                } else if (longString && s.startsWith(String.valueOf(quote).repeat(3), i)) {
                    i += 3;
                    // a long string may end with extra quotes: """a"""" is a then "
                    while (i < s.length() && s.charAt(i) == quote) {
                        i++;
                    }
                    break;
                } else if (!longString && c == quote) {
                    i++;
                    break;
                } else {
                    i++;
                }
            }
            body.append(s, start, Math.min(i, s.length()));
        }

        /** At a statement start: consume and act on a directive, or return false. */
        private boolean startsDirective() {
            String word = wordAt(i);
            String upper = word.toUpperCase(Locale.ROOT);
            switch (upper) {
                case "MESSAGE" -> {
                    i += word.length();
                    cut();
                    return true;
                }
                case "@MESSAGE" -> {
                    if (!word.equals("@message")) {
                        return false;                  // @-directives are case-sensitive
                    }
                    i += word.length();
                    expectDot("@message");
                    cut();
                    return true;
                }
                case "VERSION", "@VERSION" -> {
                    boolean atForm = word.startsWith("@");
                    if (atForm && !word.equals("@version")) {
                        return false;
                    }
                    i += word.length();
                    versionLabel = readQuotedLabel();
                    if (atForm) {
                        expectDot("@version");
                    }
                    statementStart = true;
                    return true;
                }
                case "PREFIX", "BASE", "@PREFIX", "@BASE" -> {
                    boolean atForm = word.startsWith("@");
                    if (atForm && !word.equals(word.toLowerCase(Locale.ROOT))) {
                        return false;
                    }
                    copyDirective(atForm);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        /** The run of letters (with an optional leading '@') starting at {@code p}. */
        private String wordAt(int p) {
            int end = p;
            if (end < s.length() && s.charAt(end) == '@') {
                end++;
            }
            while (end < s.length() && Character.isLetter(s.charAt(end))) {
                end++;
            }
            // MESSAGE must be a whole token: "MESSAGES" or "MESSAGE:x" is not the delimiter
            if (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == ':'
                    || s.charAt(end) == '_' || s.charAt(end) == '-')) {
                return "";
            }
            return s.substring(p, end);
        }

        /** Copy a PREFIX/BASE directive into the current body and the running header. */
        private void copyDirective(boolean atForm) {
            int start = i;
            // a directive ends after its IRI (SPARQL form) or after the '.' that follows it (@ form)
            while (i < s.length() && s.charAt(i) != '<') {
                if (s.charAt(i) == '#') {
                    throw new IllegalArgumentException("malformed directive at offset " + start);
                }
                i++;
            }
            if (i < s.length()) {
                i++;
                while (i < s.length() && s.charAt(i) != '>') {
                    i++;
                }
                i = Math.min(s.length(), i + 1);
            }
            if (atForm) {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i < s.length() && s.charAt(i) == '.') {
                    i++;
                }
            }
            String directive = s.substring(start, i);
            body.append(directive);
            header.append(directive).append('\n');
            statementStart = true;
        }

        private String readQuotedLabel() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= s.length() || (s.charAt(i) != '"' && s.charAt(i) != '\'')) {
                throw new IllegalArgumentException("VERSION must be followed by a quoted label");
            }
            char q = s.charAt(i++);
            int start = i;
            while (i < s.length() && s.charAt(i) != q) {
                i++;
            }
            String label = s.substring(start, Math.min(i, s.length()));
            i = Math.min(s.length(), i + 1);
            return label;
        }

        private void expectDot(String directive) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) != '.') {
                throw new IllegalArgumentException(directive + " must end with '.'");
            }
            i++;
        }

        /** End the current message at a delimiter. */
        private void cut() {
            delimiters++;
            segments.add(new Segment(segmentHeader, body.toString()));
            segmentHeader = header.toString();
            body = new StringBuilder();
            statementStart = true;
        }
    }
}
