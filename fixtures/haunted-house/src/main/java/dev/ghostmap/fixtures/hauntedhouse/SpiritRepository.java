package dev.ghostmap.fixtures.hauntedhouse;

import java.util.Optional;

public interface SpiritRepository {
    void reset();

    void save(Spirit spirit);

    Optional<Spirit> findByName(String name);

    int count();
}
