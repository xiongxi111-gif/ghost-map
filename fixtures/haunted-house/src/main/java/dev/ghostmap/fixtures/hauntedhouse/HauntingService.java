package dev.ghostmap.fixtures.hauntedhouse;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class HauntingService implements HauntingOperations {
    private final SpiritRepository repository;

    @Qualifier("whisperingAnnouncer")
    private final ApparitionAnnouncer announcer;

    @Override
    @Transactional
    public String recordSighting(String spiritName) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("recordSighting must run through the transactional proxy");
        }
        Spirit spirit = new Spirit();
        spirit.setName(spiritName);
        repository.save(spirit);
        return announcer.announce(spirit);
    }
}
