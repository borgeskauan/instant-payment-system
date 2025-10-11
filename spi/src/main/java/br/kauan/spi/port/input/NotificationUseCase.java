package br.kauan.spi.port.input;

import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import reactor.core.publisher.Mono;

public interface NotificationUseCase {
    Mono<SpiNotification> getNotifications(String ispb);
}
