package br.kauan.spi.adapter.input;

import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
public class PaymentController {

    private final NotificationUseCase notificationUseCase;

    public PaymentController(NotificationUseCase notificationUseCase) {
        this.notificationUseCase = notificationUseCase;
    }

    @GetMapping("/{ispb}/messages")
    public DeferredResult<SpiNotification> getMessages(@PathVariable String ispb) {
      return notificationUseCase.getNotifications(ispb);
    }
}
