package br.kauan.spi.application.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class NotificationContentSerializer {

    private final ObjectMapper objectMapper;

    public NotificationContentSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] serialize(Object obj) {
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            throw new NotificationException(
                    "Failed to serialize notification payload of type " + obj.getClass().getSimpleName(),
                    e
            );
        }
    }
}
