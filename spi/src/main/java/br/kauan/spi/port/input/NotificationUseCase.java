package br.kauan.spi.port.input;

import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import org.springframework.web.context.request.async.DeferredResult;

public interface NotificationUseCase {
    DeferredResult<SpiNotification> getNotifications(String ispb);
}
