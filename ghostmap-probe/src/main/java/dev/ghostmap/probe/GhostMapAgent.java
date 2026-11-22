package dev.ghostmap.probe;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

/** JVM agent entry point that installs real Byte Buddy method instrumentation. */
public final class GhostMapAgent {
    private GhostMapAgent() {
    }

    /** JVM instrumentation entry point invoked by {@code -javaagent}. */
    public static void premain(String agentArguments, Instrumentation instrumentation) {
        ProbeLog startupLog = new ProbeLog(ProbeOptions.bestEffortLogPath(agentArguments));
        String previousSafeSetting = null;
        boolean safeSettingChanged = false;
        try {
            previousSafeSetting = System.getProperty("net.bytebuddy.safe");
            System.setProperty("net.bytebuddy.safe", "true");
            safeSettingChanged = true;
            ProbeOptions options = ProbeOptions.parse(agentArguments);
            ProbeLog log = new ProbeLog(options.log());
            RuntimeDumpWriter writer = new RuntimeDumpWriter(options.output());
            ProbeSession session = new ProbeSession(options, log, writer);
            ProbeRuntime.install(session);
            session.start();

            new AgentBuilder.Default()
                    .ignore(alwaysExcluded())
                    .type(included(options).and(not(excluded(options))))
                    .transform((builder, type, classLoader, module, protectionDomain) -> builder.visit(
                            Advice.to(ProbeAdvice.class).on(isMethod().and(not(isAbstract())).and(not(isNative())))))
                    .with(new FailureListener())
                    .installOn(instrumentation);
            log.info("INSTRUMENTATION_INSTALLED includes=" + String.join(";", options.includes()));
        } catch (Throwable failure) {
            startupLog.error("DISABLED agent startup failed", failure);
            ProbeRuntime.disable("agent startup failed", failure);
        } finally {
            if (safeSettingChanged) {
                try {
                    if (previousSafeSetting == null) {
                        System.clearProperty("net.bytebuddy.safe");
                    } else {
                        System.setProperty("net.bytebuddy.safe", previousSafeSetting);
                    }
                } catch (RuntimeException failure) {
                    startupLog.error("could not restore Byte Buddy safety property", failure);
                }
            }
        }
    }

    private static ElementMatcher.Junction<TypeDescription> included(ProbeOptions options) {
        ElementMatcher.Junction<TypeDescription> matcher = none();
        for (String prefix : options.includes()) {
            matcher = matcher.or(nameStartsWith(prefix + '.'));
        }
        return matcher;
    }

    private static ElementMatcher.Junction<TypeDescription> excluded(ProbeOptions options) {
        ElementMatcher.Junction<TypeDescription> matcher = none();
        for (String prefix : options.excludes()) {
            matcher = matcher.or(nameStartsWith(prefix + '.'));
        }
        return matcher;
    }

    private static ElementMatcher.Junction<TypeDescription> alwaysExcluded() {
        return nameStartsWith("dev.ghostmap.probe.")
                .or(nameStartsWith("java."))
                .or(nameStartsWith("jdk."))
                .or(nameStartsWith("sun."))
                .or(nameStartsWith("com.sun."));
    }

    private static final class FailureListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                            Throwable throwable) {
            ProbeRuntime.disable("instrumentation failed for " + typeName, throwable);
        }
    }
}
