package br.kauan.paymentserviceprovider.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
        GlobalVariables.bankCode = bankCode;
    }
}
