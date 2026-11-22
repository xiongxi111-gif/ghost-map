package dev.ghostmap.model;

/** Validation helpers for erased JVM field and method descriptors. */
public final class JvmDescriptors {
    private JvmDescriptors() {
    }

    public static String requireFieldDescriptor(String descriptor) {
        ModelChecks.nonBlank(descriptor, "descriptor");
        int end = parseType(descriptor, 0, false);
        if (end != descriptor.length()) {
            throw invalid(descriptor);
        }
        return descriptor;
    }

    public static String requireMethodDescriptor(String descriptor) {
        ModelChecks.nonBlank(descriptor, "descriptor");
        if (descriptor.charAt(0) != '(') {
            throw invalid(descriptor);
        }
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            index = parseType(descriptor, index, false);
        }
        if (index >= descriptor.length() || descriptor.charAt(index) != ')') {
            throw invalid(descriptor);
        }
        index++;
        if (index >= descriptor.length()) {
            throw invalid(descriptor);
        }
        int end = descriptor.charAt(index) == 'V' ? index + 1 : parseType(descriptor, index, false);
        if (end != descriptor.length()) {
            throw invalid(descriptor);
        }
        return descriptor;
    }

    private static int parseType(String descriptor, int start, boolean arrayElement) {
        if (start >= descriptor.length()) {
            throw invalid(descriptor);
        }
        char symbol = descriptor.charAt(start);
        if ("BCDFIJSZ".indexOf(symbol) >= 0) {
            return start + 1;
        }
        if (symbol == '[') {
            return parseType(descriptor, start + 1, true);
        }
        if (symbol == 'L') {
            int semicolon = descriptor.indexOf(';', start + 1);
            if (semicolon < 0 || semicolon == start + 1) {
                throw invalid(descriptor);
            }
            String internalName = descriptor.substring(start + 1, semicolon);
            if (internalName.indexOf('.') >= 0 || internalName.indexOf('[') >= 0
                    || internalName.indexOf('(') >= 0 || internalName.indexOf(')') >= 0) {
                throw invalid(descriptor);
            }
            return semicolon + 1;
        }
        if (arrayElement && symbol == 'V') {
            throw invalid(descriptor);
        }
        throw invalid(descriptor);
    }

    private static IllegalArgumentException invalid(String descriptor) {
        return new IllegalArgumentException("invalid JVM descriptor: " + descriptor);
    }
}
