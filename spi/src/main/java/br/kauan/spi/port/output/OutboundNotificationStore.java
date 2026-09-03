package br.kauan.spi.port.output;

import br.kauan.spi.application.notification.OutboundNotification;

import java.util.List;

public interface OutboundNotificationStore {
    void insertAll(List<OutboundNotification> notifications);
}
