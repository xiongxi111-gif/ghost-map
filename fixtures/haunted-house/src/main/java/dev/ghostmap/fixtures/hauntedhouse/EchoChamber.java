package dev.ghostmap.fixtures.hauntedhouse;

public class EchoChamber {
    private String prefix;
    private ApparitionAnnouncer announcer;

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public void setAnnouncer(ApparitionAnnouncer announcer) {
        this.announcer = announcer;
    }

    public String echo(Spirit spirit) {
        return prefix + announcer.announce(spirit);
    }
}
