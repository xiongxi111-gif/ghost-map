package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.TypeId;

import java.util.Set;

/** Bean candidate shared by XML and annotation injection resolution. */
record WiringBeanRegistration(BeanId beanId, TypeId type, Set<String> names,
                              Set<String> qualifiers, boolean primary) {
    WiringBeanRegistration {
        if (beanId == null || type == null || names == null || qualifiers == null) {
            throw new NullPointerException("bean registration fields must not be null");
        }
        names = Set.copyOf(names);
        qualifiers = Set.copyOf(qualifiers);
    }
}
