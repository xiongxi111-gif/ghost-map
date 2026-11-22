package dev.ghostmap.fixtures.hauntedhouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = HauntedHouseConfig.class)
class HauntedHouseIntegrationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private HauntedHouseTour tour;

    @Autowired
    private SpiritRepository repository;

    @Autowired
    private BeanSummoner beanSummoner;

    @Autowired
    private ReflectiveInvoker reflectiveInvoker;

    @Autowired
    private HauntingRegistry registry;

    @BeforeEach
    void resetDatabase() {
        repository.reset();
    }

    @Test
    void transactionalProxyUsesQualifiedImplementationAndJdbc() {
        HauntingOperations operations = applicationContext.getBean(HauntingOperations.class);
        assertTrue(AopUtils.isAopProxy(operations));

        assertEquals("whisper:Ada", tour.visit("Ada"));
        assertEquals(1, repository.count());

        Spirit stored = repository.findByName("Ada").orElseThrow();
        assertTrue(stored.getId() > 0);
        assertEquals("Ada", stored.getName());
    }

    @Test
    void xmlChildInheritsClassPropertiesAndBeanReference() {
        EchoChamber chamber = applicationContext.getBean("ancestralEcho", EchoChamber.class);
        assertEquals("ancestral:rattle:Mist", chamber.echo(spiritNamed("Mist")));
    }

    @Test
    void constantAndVariableBeanLookupsBothExecute() {
        Spirit spirit = spiritNamed("Lantern");
        assertEquals("ancestral:rattle:Lantern", beanSummoner.summonKnown(spirit));
        assertEquals("whisper:Lantern", beanSummoner.summonChosen("whisperingAnnouncer", spirit));
    }

    @Test
    void methodInvokeExecutesRealImplementation() throws ReflectiveOperationException {
        assertEquals("whisper:Mirror", reflectiveInvoker.invoke(spiritNamed("Mirror")));
    }

    @Test
    void stringKeyedRegistryDispatchesSelectedHandler() {
        assertEquals("rattle:Keys", registry.dispatch("rattlingAnnouncer", spiritNamed("Keys")));
    }

    private static Spirit spiritNamed(String name) {
        Spirit spirit = new Spirit();
        spirit.setName(name);
        return spirit;
    }
}
