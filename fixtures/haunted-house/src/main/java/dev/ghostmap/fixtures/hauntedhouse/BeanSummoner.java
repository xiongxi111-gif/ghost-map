package dev.ghostmap.fixtures.hauntedhouse;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BeanSummoner {
    private final ApplicationContext applicationContext;

    public String summonKnown(Spirit spirit) {
        EchoChamber chamber = applicationContext.getBean("ancestralEcho", EchoChamber.class);
        return chamber.echo(spirit);
    }

    public String summonChosen(String beanName, Spirit spirit) {
        ApparitionAnnouncer announcer = (ApparitionAnnouncer) applicationContext.getBean(beanName);
        return announcer.announce(spirit);
    }
}
