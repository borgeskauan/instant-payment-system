package br.kauan.spi.domain.services.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class NotificationContentSerializer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<String> serialize(Object obj) {
        try {
            return Optional.of(objectMapper.writeValueAsString(obj));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object of type: {}", obj.getClass().getSimpleName(), e);
            return Optional.empty();
        }
    }
}