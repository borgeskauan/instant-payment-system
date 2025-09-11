package br.kauan.spi.domain.services;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SpiNotification {
    private String content;
}
