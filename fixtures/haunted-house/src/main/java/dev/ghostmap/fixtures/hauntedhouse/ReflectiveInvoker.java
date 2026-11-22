package dev.ghostmap.fixtures.hauntedhouse;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
@RequiredArgsConstructor
public class ReflectiveInvoker {
    @Qualifier("whisperingAnnouncer")
    private final ApparitionAnnouncer announcer;

    public String invoke(Spirit spirit) throws ReflectiveOperationException {
        Method method = ApparitionAnnouncer.class.getMethod("announce", Spirit.class);
        return (String) method.invoke(announcer, spirit);
    }
}
