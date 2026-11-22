package dev.ghostmap.probe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Validated comma-separated {@code -javaagent} options. */
record ProbeOptions(List<String> includes, List<String> excludes, Path output, int edgeLimit,
                    Optional<Path> trigger, long triggerPollMillis, Path log, String runId) {
    static final int DEFAULT_EDGE_LIMIT = 100_000;
    static final long DEFAULT_TRIGGER_POLL_MILLIS = 250;

    ProbeOptions {
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
        if (includes.isEmpty()) {
            throw new IllegalArgumentException("include is required");
        }
        if (output == null || trigger == null || log == null) {
            throw new NullPointerException("probe paths must not be null");
        }
        if (edgeLimit < 1) {
            throw new IllegalArgumentException("edgeLimit must be positive");
        }
        if (triggerPollMillis < 10) {
            throw new IllegalArgumentException("triggerPollMillis must be at least 10");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Path normalizedOutput = output.toAbsolutePath().normalize();
        Path normalizedLog = log.toAbsolutePath().normalize();
        if (normalizedOutput.equals(normalizedLog)
                || trigger.map(path -> path.toAbsolutePath().normalize()).filter(normalizedOutput::equals).isPresent()
                || trigger.map(path -> path.toAbsolutePath().normalize()).filter(normalizedLog::equals).isPresent()) {
            throw new IllegalArgumentException("out, log, and trigger must be different paths");
        }
    }

    static ProbeOptions parse(String agentArguments) {
        Map<String, String> values = parseValues(agentArguments);
        rejectUnknown(values);
        List<String> includes = prefixes(required(values, "include"), "include");
        List<String> excludes = values.containsKey("exclude")
                ? prefixes(values.get("exclude"), "exclude") : List.of();
        Path output = Path.of(values.getOrDefault("out", "runtime-edges.jsonl"));
        int edgeLimit = positiveInt(values.getOrDefault("edgeLimit", Integer.toString(DEFAULT_EDGE_LIMIT)),
                "edgeLimit");
        Optional<Path> trigger = optionalPath(values.get("trigger"));
        long pollMillis = positiveLong(values.getOrDefault("triggerPollMillis",
                Long.toString(DEFAULT_TRIGGER_POLL_MILLIS)), "triggerPollMillis");
        Path log = Path.of(values.getOrDefault("log", "ghostmap-probe.log"));
        String runId = values.getOrDefault("runId", UUID.randomUUID().toString()).strip();
        return new ProbeOptions(includes, excludes, output, edgeLimit, trigger, pollMillis, log, runId);
    }

    static Path bestEffortLogPath(String agentArguments) {
        try {
            String configured = parseValues(agentArguments).get("log");
            return configured == null || configured.isBlank() ? Path.of("ghostmap-probe.log") : Path.of(configured);
        } catch (RuntimeException ignored) {
            return Path.of("ghostmap-probe.log");
        }
    }

    boolean includesType(String binaryName) {
        return matchesAny(binaryName, includes) && !matchesAny(binaryName, excludes)
                && !isAlwaysExcluded(binaryName);
    }

    private static Map<String, String> parseValues(String arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        if (arguments == null || arguments.isBlank()) {
            return values;
        }
        for (String part : arguments.split(",", -1)) {
            int equals = part.indexOf('=');
            if (equals < 1) {
                throw new IllegalArgumentException("agent option must be key=value: " + part);
            }
            String key = part.substring(0, equals).strip();
            String value = part.substring(equals + 1).strip();
            if (key.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("agent option key and value must not be blank");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate agent option: " + key);
            }
        }
        return values;
    }

    private static void rejectUnknown(Map<String, String> values) {
        List<String> allowed = List.of("include", "exclude", "out", "edgeLimit", "trigger",
                "triggerPollMillis", "log", "runId");
        values.keySet().stream().filter(key -> !allowed.contains(key)).findFirst()
                .ifPresent(key -> {
                    throw new IllegalArgumentException("unknown agent option: " + key);
                });
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static List<String> prefixes(String value, String name) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(";", -1)) {
            String prefix = normalizePrefix(part);
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException(name + " contains a blank package prefix");
            }
            if (!result.contains(prefix)) {
                result.add(prefix);
            }
        }
        return List.copyOf(result);
    }

    private static String normalizePrefix(String value) {
        String result = value.strip();
        if (result.endsWith(".*")) {
            result = result.substring(0, result.length() - 2);
        }
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.contains("/") || result.contains(" ")) {
            throw new IllegalArgumentException("package filters must use dotted binary names: " + value);
        }
        return result;
    }

    private static Optional<Path> optionalPath(String value) {
        return value == null ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static int positiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static long positiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static boolean matchesAny(String binaryName, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> binaryName.equals(prefix) || binaryName.startsWith(prefix + '.'));
    }

    private static boolean isAlwaysExcluded(String binaryName) {
        return binaryName.startsWith("dev.ghostmap.probe.")
                || binaryName.equals("dev.ghostmap.probe")
                || binaryName.startsWith("java.")
                || binaryName.startsWith("jdk.")
                || binaryName.startsWith("sun.")
                || binaryName.startsWith("com.sun.");
    }
}
