package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.EntryId;
import dev.ghostmap.model.CanonicalId.ExternalId;
import dev.ghostmap.model.CanonicalId.FieldId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;

/** Formats and parses every canonical id from the node identity table. */
public final class CanonicalIds {
    private CanonicalIds() {
    }

    public static String format(CanonicalId id) {
        if (id == null) {
            throw new NullPointerException("id");
        }
        return id.value();
    }

    public static CanonicalId parse(String value) {
        ModelChecks.nonBlank(value, "canonical id");
        if (value.length() < 3 || value.charAt(1) != ':') {
            throw invalid(value);
        }
        return switch (value.charAt(0)) {
            case 't' -> new TypeId(value.substring(2));
            case 'm' -> parseMethod(value);
            case 'f' -> parseField(value);
            case 'b' -> parseBean(value);
            case 'e' -> parseEntry(value);
            case 'x' -> parseExternal(value);
            case 's' -> parseSite(value);
            default -> throw invalid(value);
        };
    }

    private static MethodId parseMethod(String value) {
        int hash = value.indexOf('#', 2);
        int descriptor = value.indexOf('(', hash + 1);
        if (hash < 3 || descriptor <= hash + 1) {
            throw invalid(value);
        }
        return new MethodId(value.substring(2, hash), value.substring(hash + 1, descriptor),
                value.substring(descriptor));
    }

    private static FieldId parseField(String value) {
        int hash = value.indexOf('#', 2);
        int colon = value.indexOf(':', hash + 1);
        if (hash < 3 || colon <= hash + 1) {
            throw invalid(value);
        }
        return new FieldId(value.substring(2, hash), value.substring(hash + 1, colon), value.substring(colon + 1));
    }

    private static BeanId parseBean(String value) {
        int hash = value.indexOf('#', 2);
        if (hash < 3 || hash == value.length() - 1) {
            throw invalid(value);
        }
        return new BeanId(value.substring(2, hash), value.substring(hash + 1));
    }

    private static EntryId parseEntry(String value) {
        int colon = value.indexOf(':', 2);
        if (colon < 3 || colon == value.length() - 1) {
            throw invalid(value);
        }
        return new EntryId(value.substring(2, colon), value.substring(colon + 1));
    }

    private static ExternalId parseExternal(String value) {
        int colon = value.indexOf(':', 2);
        if (colon < 3 || colon == value.length() - 1) {
            throw invalid(value);
        }
        return new ExternalId(value.substring(2, colon), value.substring(colon + 1));
    }

    private static SiteId parseSite(String value) {
        int at = value.lastIndexOf('@');
        if (at < 4 || at == value.length() - 1) {
            throw invalid(value);
        }
        CanonicalId method = parse(value.substring(2, at));
        if (!(method instanceof MethodId methodId)) {
            throw invalid(value);
        }
        try {
            return new SiteId(methodId, Integer.parseInt(value.substring(at + 1)));
        } catch (NumberFormatException exception) {
            throw invalid(value, exception);
        }
    }

    static String requireBinaryName(String value) {
        ModelChecks.nonBlank(value, "binary name");
        if (value.indexOf('/') >= 0 || value.indexOf('#') >= 0 || value.indexOf(':') >= 0
                || value.indexOf(';') >= 0 || value.indexOf('[') >= 0) {
            throw new IllegalArgumentException("invalid binary name: " + value);
        }
        return value;
    }

    static String requireMemberName(String value, String name) {
        ModelChecks.nonBlank(value, name);
        if (value.indexOf('#') >= 0 || value.indexOf(':') >= 0 || value.indexOf('(') >= 0) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    static String requireDelimitedPart(String value, String name, char delimiter) {
        ModelChecks.nonBlank(value, name);
        if (value.indexOf(delimiter) >= 0) {
            throw new IllegalArgumentException(name + " must not contain '" + delimiter + "'");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("invalid canonical id: " + value);
    }

    private static IllegalArgumentException invalid(String value, Exception cause) {
        return new IllegalArgumentException("invalid canonical id: " + value, cause);
    }
}
