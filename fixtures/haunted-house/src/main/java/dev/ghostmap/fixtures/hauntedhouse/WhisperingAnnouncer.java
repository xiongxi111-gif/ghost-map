package dev.ghostmap.fixtures.hauntedhouse;

import org.springframework.stereotype.Component;

@Component("whisperingAnnouncer")
public class WhisperingAnnouncer implements ApparitionAnnouncer {
    @Override
    public String announce(Spirit spirit) {
        return "whisper:" + spirit.getName();
    }
}
