package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.TypeId;

import java.util.Optional;

/** Injection point decoded from field, constructor, method, or factory-method bytecode. */
record AnnotationInjectionSite(BeanId ownerBean, WiringInjectionPoint point, TypeId requiredType,
                               Optional<String> qualifier, Optional<String> resourceName,
                               boolean explicitResourceName, String classFile, int line) {
    AnnotationInjectionSite {
        if (ownerBean == null || point == null || requiredType == null || qualifier == null
                || resourceName == null || classFile == null) {
            throw new NullPointerException("annotation injection fields must not be null");
        }
        if (classFile.isBlank() || line < 1) {
            throw new IllegalArgumentException("annotation injection requires a class location");
        }
    }
}
