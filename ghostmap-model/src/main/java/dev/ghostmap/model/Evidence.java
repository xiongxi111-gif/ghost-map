package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.BeanId;

/** Locatable evidence contributed by one analysis layer. */
public sealed interface Evidence permits Evidence.Ast, Evidence.Bytecode, Evidence.Wiring, Evidence.Runtime {
    LayerId layer();

    record Ast(String file, int line, String text) implements Evidence {
        public Ast {
            ModelChecks.nonBlank(file, "file");
            ModelChecks.positive(line, "line");
            ModelChecks.nonBlank(text, "text");
        }

        @Override
        public LayerId layer() {
            return LayerId.AST;
        }
    }

    record Bytecode(String classFile, int offset, String instruction, String owner,
                    String name, String descriptor) implements Evidence {
        public Bytecode {
            ModelChecks.nonBlank(classFile, "classFile");
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            ModelChecks.nonBlank(instruction, "instruction");
            ModelChecks.nonBlank(owner, "owner");
            ModelChecks.nonBlank(name, "name");
            JvmDescriptors.requireMethodDescriptor(descriptor);
        }

        @Override
        public LayerId layer() {
            return LayerId.BYTECODE;
        }
    }

    record Wiring(String file, int line, BeanId bean) implements Evidence {
        public Wiring {
            ModelChecks.nonBlank(file, "file");
            ModelChecks.positive(line, "line");
            if (bean == null) {
                throw new NullPointerException("bean");
            }
        }

        @Override
        public LayerId layer() {
            return LayerId.WIRING;
        }
    }

    record Runtime(String runId, long hits) implements Evidence {
        public Runtime {
            ModelChecks.nonBlank(runId, "runId");
            if (hits < 1) {
                throw new IllegalArgumentException("hits must be positive");
            }
        }

        @Override
        public LayerId layer() {
            return LayerId.RUNTIME;
        }
    }
}
