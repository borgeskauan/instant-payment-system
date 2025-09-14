package br.kauan.paymentserviceprovider.adapter.output;

import lombok.Data;

import java.util.List;

@Data
public class SpiNotification {
    private List<Object> content;
}
