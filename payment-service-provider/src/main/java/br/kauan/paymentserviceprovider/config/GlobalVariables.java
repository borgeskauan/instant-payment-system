package br.kauan.paymentserviceprovider.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GlobalVariables {

    @Value("${bank.code}")
    public static String BANK_CODE;
}
