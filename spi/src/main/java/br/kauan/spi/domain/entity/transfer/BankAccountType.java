package br.kauan.spi.domain.entity.transfer;

public enum BankAccountType {
    CHECKING,
    SAVINGS,
    SALARY,
    PAYMENT;

    public static BankAccountType fromString(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }

        for (BankAccountType accountType : BankAccountType.values()) {
            if (accountType.name().equalsIgnoreCase(type)) {
                return accountType;
            }
        }

        throw new IllegalArgumentException("Unknown bank account type: " + type);
    }
}
