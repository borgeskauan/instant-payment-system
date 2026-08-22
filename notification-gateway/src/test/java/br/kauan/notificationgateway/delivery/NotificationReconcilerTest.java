package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationReconcilerTest {

    @Test
    void repositoryCanBeProxiedBySpring() {
        NotificationReconciliationRepository repository =
                new NotificationReconciliationRepository(mock(org.springframework.jdbc.core.JdbcTemplate.class));
        ProxyFactory proxyFactory = new ProxyFactory(repository);
        proxyFactory.setProxyTargetClass(true);

        assertDoesNotThrow(() -> {
            proxyFactory.getProxy();
        });
    }

    @Test
    void emptyScanHasNoIndexingEffects() {
        NotificationReconciliationRepository repository = mock(NotificationReconciliationRepository.class);
        NotificationIndexingService indexingService = mock(NotificationIndexingService.class);
        when(repository.findUnindexedAfter("", 2)).thenReturn(List.of());

        new NotificationReconciler(repository, indexingService, 2).reconcile();

        verifyNoInteractions(indexingService);
    }

    @Test
    void drainsPagesAndKeepsRecipientGroupsSeparate() {
        NotificationReconciliationRepository repository = mock(NotificationReconciliationRepository.class);
        NotificationIndexingService indexingService = mock(NotificationIndexingService.class);
        IncomingNotification first = incoming("v1:a", "20000001");
        IncomingNotification second = incoming("v1:b", "20000002");
        IncomingNotification third = incoming("v1:c", "20000001");
        when(repository.findUnindexedAfter("", 2)).thenReturn(List.of(first, second));
        when(repository.findUnindexedAfter("v1:b", 2)).thenReturn(List.of(third));

        new NotificationReconciler(repository, indexingService, 2).reconcile();

        var order = inOrder(repository, indexingService);
        order.verify(repository).findUnindexedAfter("", 2);
        order.verify(indexingService).ensureIndexed(List.of(first));
        order.verify(indexingService).ensureIndexed(List.of(second));
        order.verify(repository).findUnindexedAfter("v1:b", 2);
        order.verify(indexingService).ensureIndexed(List.of(third));
    }

    @Test
    void oneRecipientFailureDoesNotBlockOtherRecipients() {
        NotificationReconciliationRepository repository = mock(NotificationReconciliationRepository.class);
        NotificationIndexingService indexingService = mock(NotificationIndexingService.class);
        IncomingNotification failing = incoming("v1:a", "20000001");
        IncomingNotification healthy = incoming("v1:b", "20000002");
        when(repository.findUnindexedAfter("", 10)).thenReturn(List.of(failing, healthy));
        doThrow(new IllegalStateException("broken recipient"))
                .when(indexingService).ensureIndexed(List.of(failing));

        assertDoesNotThrow(() -> new NotificationReconciler(repository, indexingService, 10).reconcile());

        var order = inOrder(indexingService);
        order.verify(indexingService).ensureIndexed(List.of(failing));
        order.verify(indexingService).ensureIndexed(List.of(healthy));
    }

    @Test
    void databaseFailureEndsTheCycleWithoutEscapingTheScheduler() {
        NotificationReconciliationRepository repository = mock(NotificationReconciliationRepository.class);
        NotificationIndexingService indexingService = mock(NotificationIndexingService.class);
        when(repository.findUnindexedAfter("", 10)).thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> new NotificationReconciler(repository, indexingService, 10).reconcile());

        verifyNoInteractions(indexingService);
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new NotificationReconciler(
                mock(NotificationReconciliationRepository.class),
                mock(NotificationIndexingService.class),
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reconciliation batch size must be positive");
    }

    private IncomingNotification incoming(String communicationId, String recipientIspb) {
        return new IncomingNotification(
                communicationId,
                recipientIspb,
                communicationId.getBytes(StandardCharsets.UTF_8)
        );
    }
}
