package dev.ghostmap.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Ghost Map command-line entry point. */
@Command(name = "ghostmap", mixinStandardHelpOptions = true,
        description = "Build and inspect evidence-graded Java code maps.",
        subcommands = IndexCommand.class)
public final class GhostMapCli implements Runnable {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GhostMapCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
