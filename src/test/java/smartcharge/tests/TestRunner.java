/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.tests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Dependency-free test runner. For each test it records ID, input, expected and observed
 * behaviour and the pass/fail outcome, and captures the thread-named station log as evidence.
 * Must run with assertions enabled (-ea) because design-contract checks use assert.
 *
 * Usage: TestRunner [outputDir] [testId ...]
 */
public final class TestRunner {

    /** Result of executing one test body. */
    public record Outcome(String observed, boolean pass, List<String> evidence) {
        public static Outcome of(String observed, boolean pass, String... evidence) {
            return new Outcome(observed, pass, Arrays.asList(evidence));
        }
    }

    @FunctionalInterface
    public interface Body {
        Outcome run() throws Exception;
    }

    /** Test definition. category is NORMAL, EDGE or OBSERVATION (informational, never fails). */
    public record Case(String id, String category, String title, String input, String expected, Body body) {
    }

    private record Executed(Case testCase, Outcome outcome, String log, long millis) {
        String verdict() {
            if (testCase.category().equals("OBSERVATION")) {
                return "OBSERVED";
            }
            return outcome.pass() ? "PASS" : "FAIL";
        }
    }

    private TestRunner() {
    }

    public static void main(String[] args) throws IOException {
        Thread.currentThread().setName("test-main");
        if (!assertionsEnabled()) {
            System.err.println("Assertions are disabled. Run with: java -ea ...");
            System.exit(2);
        }
        Path outDir = Path.of(args.length > 0 ? args[0] : "results");
        List<String> only = args.length > 1 ? Arrays.asList(args).subList(1, args.length) : List.of();
        Files.createDirectories(outDir.resolve("test-logs"));

        PrintStream console = System.out;
        List<Executed> executed = new ArrayList<>();
        for (Case c : SmartChargeTests.all()) {
            if (!only.isEmpty() && !only.contains(c.id())) {
                continue;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
            long t0 = System.nanoTime();
            Outcome outcome;
            System.setOut(capture);
            try {
                outcome = c.body().run();
            } catch (Throwable t) {
                outcome = Outcome.of("threw " + t, false);
            } finally {
                System.setOut(console);
            }
            long millis = (System.nanoTime() - t0) / 1_000_000;
            String log = buffer.toString(StandardCharsets.UTF_8);
            Executed e = new Executed(c, outcome, log, millis);
            executed.add(e);
            Files.writeString(outDir.resolve("test-logs").resolve(c.id() + ".txt"), log + String.join("\n", outcome.evidence()) + "\n");
            console.printf(Locale.ROOT, "%-4s %-11s %-8s %5d ms  %s%n      observed: %s%n", c.id(), c.category(), e.verdict(),
                    millis, c.title(), outcome.observed());
        }

        long failed = executed.stream().filter(e -> e.verdict().equals("FAIL")).count();
        long passed = executed.stream().filter(e -> e.verdict().equals("PASS")).count();
        Files.writeString(outDir.resolve("test-results.md"), report(executed, passed, failed));
        console.printf(Locale.ROOT, "%nTOTAL: %d passed, %d failed, %d observations. Report: %s%n", passed, failed,
                executed.size() - passed - failed, outDir.resolve("test-results.md"));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String report(List<Executed> executed, long passed, long failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SmartCharge test results\n\n");
        sb.append("Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append("  \nJava: ").append(System.getProperty("java.version"))
                .append("  \nAssertions enabled: ").append(assertionsEnabled())
                .append("  \nSummary: ").append(passed).append(" passed, ").append(failed).append(" failed\n\n");
        sb.append("| ID | Category | Scenario | Input / precondition | Expected | Observed | Result |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Executed e : executed) {
            Case c = e.testCase();
            sb.append("| ").append(c.id()).append(" | ").append(c.category()).append(" | ").append(cell(c.title()))
                    .append(" | ").append(cell(c.input())).append(" | ").append(cell(c.expected()))
                    .append(" | ").append(cell(e.outcome().observed())).append(" | **").append(e.verdict()).append("** |\n");
        }
        sb.append("\n## Evidence\n\nFull per-test logs: `test-logs/<ID>.txt`. Excerpts (thread name in brackets):\n");
        for (Executed e : executed) {
            sb.append("\n### ").append(e.testCase().id()).append(" - ").append(e.testCase().title())
                    .append(" (").append(e.millis()).append(" ms)\n\n```\n");
            List<String> lines = e.log().lines().toList();
            int limit = 30;
            lines.stream().limit(limit).forEach(l -> sb.append(l).append('\n'));
            if (lines.size() > limit) {
                sb.append("... (").append(lines.size() - limit).append(" more lines in test-logs/").append(e.testCase().id()).append(".txt)\n");
            }
            e.outcome().evidence().forEach(l -> sb.append(l).append('\n'));
            sb.append("```\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    static boolean assertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }
}
