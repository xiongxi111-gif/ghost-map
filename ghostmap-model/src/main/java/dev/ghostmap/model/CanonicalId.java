package dev.ghostmap.model;

/** A canonical graph node identifier shared by every analysis layer. */
public sealed interface CanonicalId permits CanonicalId.TypeId, CanonicalId.MethodId, CanonicalId.FieldId,
        CanonicalId.BeanId, CanonicalId.EntryId, CanonicalId.ExternalId, CanonicalId.SiteId {

    NodeKind kind();

    String value();

    record TypeId(String binaryName) implements CanonicalId {
        public TypeId {
            CanonicalIds.requireBinaryName(binaryName);
        }

        @Override
        public NodeKind kind() {
            return NodeKind.TYPE;
        }

        @Override
        public String value() {
            return "t:" + binaryName;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    record MethodId(String binaryOwner, String name, String descriptor) implements CanonicalId {
        public MethodId {
            CanonicalIds.requireBinaryName(binaryOwner);
            CanonicalIds.requireMemberName(name, "method name");
            JvmDescriptors.requireMethodDescriptor(descriptor);
        }

        @Override
        public NodeKind kind() {
            return NodeKind.METHOD;
        }

        @Override
        public String value() {
            return "m:" + binaryOwner + "#" + name + descriptor;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    record FieldId(String binaryOwner, String name, String descriptor) implements CanonicalId {
        public FieldId {
            CanonicalIds.requireBinaryName(binaryOwner);
            CanonicalIds.requireMemberName(name, "field name");
            JvmDescriptors.requireFieldDescriptor(descriptor);
        }

        @Override
        public NodeKind kind() {
            return NodeKind.FIELD;
        }

        @Override
        public String value() {
            return "f:" + binaryOwner + "#" + name + ':' + descriptor;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    record BeanId(String context, String beanId) implements CanonicalId {
        public BeanId {
            CanonicalIds.requireDelimitedPart(context, "bean context", '#');
            CanonicalIds.requireDelimitedPart(beanId, "bean id", '#');
        }

        @Override
        public NodeKind kind() {
            return NodeKind.BEAN;
        }

        @Override
        public String value() {
            return "b:" + context + "#" + beanId;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    record EntryId(String entryKind, String trigger) implements CanonicalId {
        public EntryId {
            CanonicalIds.requireDelimitedPart(entryKind, "entry kind", ':');
            ModelChecks.nonBlank(trigger, "entry trigger");
        }

        @Override
        public NodeKind kind() {
            return NodeKind.ENTRY;
        }

        @Override
        public String value() {
            return "e:" + entryKind + ':' + trigger;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    record ExternalId(String externalKind, String target) implements CanonicalId {
        public ExternalId {
            CanonicalIds.requireDelimitedPart(externalKind, "external kind", ':');
            ModelChecks.nonBlank(target, "external target");
        }

        @Override
        public NodeKind kind() {
            return NodeKind.EXTERNAL;
        }

        @Override
        public String value() {
            return "x:" + externalKind + ':' + target;
        }

        @Override
        public String toString() {
            return value();
        }
    }

    record SiteId(MethodId method, int bytecodeOffset) implements CanonicalId {
        public SiteId {
            if (method == null) {
                throw new NullPointerException("method");
            }
            if (bytecodeOffset < 0) {
                throw new IllegalArgumentException("bytecodeOffset must not be negative");
            }
        }

        @Override
        public NodeKind kind() {
            return NodeKind.SITE;
        }

        @Override
        public String value() {
            return "s:" + method.value() + '@' + bytecodeOffset;
        }

        @Override
        public String toString() {
            return value();
        }
    }
}
