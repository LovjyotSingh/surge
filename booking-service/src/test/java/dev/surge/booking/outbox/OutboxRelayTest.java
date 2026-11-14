package dev.surge.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import dev.surge.outbox.OutboxMessage;
import dev.surge.outbox.OutboxPoller;
import dev.surge.outbox.OutboxPublisher;
import dev.surge.outbox.OutboxRelay;
import dev.surge.outbox.OutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class OutboxRelayTest {

    @Autowired OutboxRelay relay;
    @Autowired OutboxPoller poller;
    @Autowired OutboxRepository repository;
    @Autowired OutboxPublisher publisher;
    @Autowired TestData testData;

    @BeforeEach
    @AfterEach
    void clean() {
        testData.clearOutbox();
    }

    private List<Long> insert(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            OutboxMessage m = new OutboxMessage("agg-" + i, "TestEvent", "test.topic", "{\"n\":" + i + "}", Instant.now());
            ids.add(repository.saveAndFlush(m).getId());
        }
        return ids;
    }

    @Test
    void publishesAndMarksRows() {
        List<Long> ids = insert(3);

        assertThat(relay.relayBatch()).isEqualTo(3);

        assertThat(repository.findAll()).hasSize(3).allSatisfy(m -> assertThat(m.getPublishedAt()).isNotNull());
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        InOrder order = inOrder(publisher);
        order.verify(publisher, org.mockito.Mockito.times(3)).publish(captor.capture());
        assertThat(captor.getAllValues()).extracting(OutboxMessage::getId).containsExactlyElementsOf(ids);
    }

    @Test
    void stopsAtFirstFailureKeepsOrder() {
        List<Long> ids = insert(3);
        doThrow(new IllegalStateException("broker down"))
                .when(publisher).publish(argThat(m -> m != null && m.getId().equals(ids.get(1))));

        assertThat(relay.relayBatch()).isEqualTo(1);

        assertThat(repository.findById(ids.get(0)).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(repository.findById(ids.get(1)).orElseThrow().getPublishedAt()).isNull();
        assertThat(repository.findById(ids.get(2)).orElseThrow().getPublishedAt()).isNull();

        org.mockito.Mockito.reset(publisher);
        assertThat(relay.relayBatch()).isEqualTo(2);
        assertThat(repository.findAll()).allSatisfy(m -> assertThat(m.getPublishedAt()).isNotNull());
    }

    @Test
    void respectsBatchSize() {
        insert(150);

        assertThat(relay.relayBatch()).isEqualTo(100);
        assertThat(relay.relayBatch()).isEqualTo(50);
        assertThat(relay.relayBatch()).isZero();
    }

    @Test
    void scheduledEntryPointCommitsPublishedRows() {
        insert(3);

        poller.poll();

        assertThat(repository.findAll()).hasSize(3).allSatisfy(m -> assertThat(m.getPublishedAt()).isNotNull());
    }
}
