package br.kauan.spi.port.input;

import br.kauan.spi.domain.services.SpiNotification;

import java.util.List;

public interface NotificationUseCase {
    List<SpiNotification> getNotifications(String ispb);
}
