package br.kauan.paymentserviceprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "psp")
public record PspProperties(String bankCode, BigDecimal initialBalance) {

    public PspProperties {
        if (bankCode == null || !bankCode.matches("\\d{8}")) {
            throw new IllegalArgumentException("psp.bank-code must contain exactly eight digits");
        }
        if (initialBalance == null || initialBalance.signum() < 0) {
            throw new IllegalArgumentException("psp.initial-balance must be non-negative");
        }
    }
}
