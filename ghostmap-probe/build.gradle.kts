// The agent must attach to older targets: compile for Java 17 bytecode.
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
