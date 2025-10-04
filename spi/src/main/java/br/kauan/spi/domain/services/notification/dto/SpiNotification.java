package br.kauan.spi.domain.services.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
@Builder
public class SpiNotification {
    private final List<String> content;

    @Builder
    private SpiNotification(List<String> content) {
        this.content = content != null ? new ArrayList<>(content) : new ArrayList<>();
    }

    public static SpiNotification empty() {
        return SpiNotification.builder()
                .content(Collections.emptyList())
                .build();
    }
}