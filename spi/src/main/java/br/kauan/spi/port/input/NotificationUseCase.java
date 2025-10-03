package br.kauan.spi.port.input;

import br.kauan.spi.domain.services.notification.dto.SpiNotification;

public interface NotificationUseCase {
    SpiNotification getNotifications(String ispb);
}
