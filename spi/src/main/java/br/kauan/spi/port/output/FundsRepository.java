package br.kauan.spi.port.output;

public interface FundsRepository {
    void provisionAccount(String bankCode, long balanceCents, boolean resetIfExists);

    long getAvailableFundsCents(String bankCode);

    void deductFunds(String bankCode, long amountCents);

    void addFunds(String bankCode, long amountCents);
}
