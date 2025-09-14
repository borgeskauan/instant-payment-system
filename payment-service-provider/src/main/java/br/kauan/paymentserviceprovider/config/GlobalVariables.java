package br.kauan.paymentserviceprovider.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GlobalVariables {

    private static String bankCode;

    public static String getBankCode() {
        if (bankCode == null || bankCode.isEmpty()) {
            throw new IllegalStateException("BANK_CODE is not initialized");
        }

        return bankCode;
    }

    @Value("${bank.code}")
    public void setBankCode(String bankCode) {
        log.info("Setting BANK_CODE to {}", bankCode);

        GlobalVariables.bankCode = bankCode;
    }
}
