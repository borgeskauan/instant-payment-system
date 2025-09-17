package br.kauan.spi.domain.services;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SpiNotification {
    private List<String> content;
}
