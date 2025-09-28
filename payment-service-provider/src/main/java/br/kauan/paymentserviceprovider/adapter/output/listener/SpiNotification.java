package br.kauan.paymentserviceprovider.adapter.output.listener;

import lombok.Data;

import java.util.List;

@Data
public class SpiNotification {
    private List<String> content;
}
