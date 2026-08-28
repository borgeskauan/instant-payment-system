package br.kauan.paymentserviceprovider.commons;

import java.util.Random;

public class Util {

    public static String generateRandomNumberString(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }

        Random random = new Random();
        StringBuilder sb = new StringBuilder();

        // First digit can't be zero
        sb.append(random.nextInt(9) + 1);

        // Remaining digits can include zero
        for (int i = 1; i < length; i++) {
            sb.append(random.nextInt(10));
        }

        return sb.toString();
    }
}
