package br.kauan.spi.port.input;

import br.kauan.spi.domain.services.SpiNotification;

public interface NotificationUseCase {
    SpiNotification getNotifications(String ispb);
}
