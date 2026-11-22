package dev.ghostmap.fixtures.hauntedhouse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HauntedHouseTour {
    private final HauntingOperations hauntingOperations;

    public String visit(String spiritName) {
        return hauntingOperations.recordSighting(spiritName);
    }
}
