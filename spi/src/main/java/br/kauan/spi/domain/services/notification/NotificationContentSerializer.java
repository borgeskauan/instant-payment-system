package br.kauan.spi.domain.services.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class NotificationContentSerializer {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Optional<String> serialize(Object obj) {
        try {
            return Optional.of(objectMapper.writeValueAsString(obj));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object of type: {}", obj.getClass().getSimpleName(), e);
            return Optional.empty();
        }
    }
}
