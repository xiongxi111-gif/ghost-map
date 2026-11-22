package dev.ghostmap.fixtures.hauntedhouse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class HauntingRegistry {
    private final Map<String, ApparitionAnnouncer> handlers;

    public String dispatch(String handlerName, Spirit spirit) {
        ApparitionAnnouncer handler = handlers.get(handlerName);
        if (handler == null) {
            throw new IllegalArgumentException("unknown handler: " + handlerName);
        }
        return handler.announce(spirit);
    }
}
