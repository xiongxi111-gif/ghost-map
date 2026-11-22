package dev.ghostmap.fixtures.hauntedhouse;

import org.springframework.stereotype.Component;

@Component("rattlingAnnouncer")
public class RattlingAnnouncer implements ApparitionAnnouncer {
    @Override
    public String announce(Spirit spirit) {
        return "rattle:" + spirit.getName();
    }
}
