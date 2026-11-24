package org.cqels.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every demo prints exactly what its expected-output file says.
 *
 * <p>Each demo with a {@code main} runs in its own JVM, so one demo's static state and system
 * properties cannot leak into another. Its standard output (not standard error, where the engine
 * logs) is compared with {@code src/test/resources/expected-output/<class>.txt}. Demos run four at
 * a time; {@code -Ddemo.parallel=N} changes that, for instance to check that none depends on timing
 * under load. Each demo JVM runs with locale en-US, time zone UTC and UTF-8, so the host's settings
 * do not change the output.
 *
 * <p>When a demo's output changes on purpose, regenerate its file on JDK 17, the JDK CI uses, and
 * review the diff ({@code Double.toString} changed in JDK 19, and many demos print doubles):
 * <pre>mvn -q test -Dtest=DemoOutputTest -Dexpected.update=true</pre>
 * With {@code expected.update} set, every demo's file is rewritten and the comparison is skipped.
 * Without it, a mismatch fails and the actual output is left in
 * {@code target/demo-output/<class>.txt}.
 */
class DemoOutputTest {

    /** Demos not run here, with the reason. */
    private static final Map<String, String> EXCLUDED = Map.of(
            "org.cqels.examples.CapabilityProbe",
            "CI tooling, not a demo: scripts/ci/capability-probe.sh runs it as the capability-truth check");

    private static final int PARALLEL = Integer.getInteger("demo.parallel", 4);
    private static final long TIMEOUT_SECONDS = 180;
    private static final Path CLASSES = Paths.get("target", "classes");
    private static final Path EXPECTED = Paths.get("src", "test", "resources", "expected-output");
    private static final Path ACTUAL = Paths.get("target", "demo-output");
    private static final boolean UPDATE = Boolean.getBoolean("expected.update");

    @TestFactory
    Stream<DynamicTest> everyDemoPrintsItsExpectedOutput() throws IOException {
        List<String> demos = new ArrayList<>(demos().keySet());
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        Map<String, Future<Run>> runs = new TreeMap<>();
        for (String demo : demos) {
            runs.put(demo, pool.submit(() -> run(demo)));
        }
        pool.shutdown();
        return runs.entrySet().stream().map(e -> dynamicTest(e.getKey(), () -> {
            Run run = e.getValue().get();
            Path expectedFile = EXPECTED.resolve(e.getKey() + ".txt");
            if (run.exitCode != 0) {   // checked first, so update mode never records a failed run's output
                fail(e.getKey() + " exited with " + run.exitCode + "\n--- stderr ---\n" + tail(run.stderr));
            }
            if (UPDATE) {
                Files.createDirectories(EXPECTED);
                Files.writeString(expectedFile, run.stdout, StandardCharsets.UTF_8);
                return;
            }
            if (!Files.exists(expectedFile)) {
                fail("no expected output for " + e.getKey() + ": run with -Dexpected.update=true and review it");
            }
            String expected = Files.readString(expectedFile, StandardCharsets.UTF_8);
            if (!expected.equals(run.stdout)) {
                Files.createDirectories(ACTUAL);
                Files.writeString(ACTUAL.resolve(e.getKey() + ".txt"), run.stdout, StandardCharsets.UTF_8);
            }
            assertEquals(expected, run.stdout, e.getKey() + " printed something else; actual output in "
                    + ACTUAL.resolve(e.getKey() + ".txt"));
        }));
    }

    /** Every expected-output file belongs to a demo that still exists, so none goes stale unnoticed. */
    @Test
    void noExpectedOutputWithoutADemo() throws IOException {
        Set<String> demos = demos().keySet();
        if (!Files.isDirectory(EXPECTED)) {
            fail("no " + EXPECTED + ": run with -Dexpected.update=true and review the files it writes");
        }
        try (Stream<Path> files = Files.list(EXPECTED)) {
            List<String> orphans = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".txt"))
                    .map(n -> n.substring(0, n.length() - ".txt".length()))
                    .filter(n -> !demos.contains(n))
                    .sorted().toList();
            assertEquals(List.of(), orphans, "expected-output files whose demo is gone or excluded");
        }
    }

    /**
     * Every class under target/classes with a {@code public static void main(String[])}, minus the
     * exclusions. A class that fails to load is an error, not "not a demo": otherwise a broken new
     * demo would never be run.
     */
    private static Map<String, Class<?>> demos() throws IOException {
        Map<String, Class<?>> demos = new TreeMap<>();
        try (Stream<Path> files = Files.walk(CLASSES)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String name = CLASSES.relativize(p).toString().replace('/', '.').replace('\\', '.');
                name = name.substring(0, name.length() - ".class".length());
                if (name.contains("$") || EXCLUDED.containsKey(name)) {
                    continue;
                }
                Class<?> c;
                try {
                    c = Class.forName(name, false, DemoOutputTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError err) {
                    throw new IllegalStateException("cannot load " + name + " to see whether it is a demo", err);
                }
                try {
                    Method main = c.getMethod("main", String[].class);
                    if (Modifier.isStatic(main.getModifiers())) {
                        demos.put(name, c);
                    }
                } catch (NoSuchMethodException notADemo) {
                    // no main: a helper class
                }
            }
        }
        return demos;
    }

    private record Run(int exitCode, String stdout, String stderr) { }

    private static Run run(String demo) throws IOException, InterruptedException {
        Path out = Files.createTempFile("demo-", ".out");
        Path err = Files.createTempFile("demo-", ".err");
        try {
            Process p = new ProcessBuilder(
                    Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                    // demos format numbers with String.format, which follows the default locale
                    // (en_DE prints 64,0); pin locale, time zone and encoding so output is the same
                    // on every machine
                    "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC",
                    "-Dfile.encoding=UTF-8",
                    "-cp", System.getProperty("java.class.path"), demo)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Run(-1, Files.readString(out), "timed out after " + TIMEOUT_SECONDS + " s\n"
                        + Files.readString(err));
            }
            return new Run(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                    Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    private static String tail(String s) {
        String[] lines = s.split("\n");
        return String.join("\n", List.of(lines).subList(Math.max(0, lines.length - 30), lines.length));
    }
}
