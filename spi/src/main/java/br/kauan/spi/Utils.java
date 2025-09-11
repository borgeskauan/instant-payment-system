package br.kauan.spi;

import br.kauan.spi.domain.entity.transfer.Party;

public class Utils {

    public static String getBankCode(Party party) {
        if (party == null || party.getAccount() == null) {
            return null;
        }

        return party.getAccount().getBankCode();
    }
}
