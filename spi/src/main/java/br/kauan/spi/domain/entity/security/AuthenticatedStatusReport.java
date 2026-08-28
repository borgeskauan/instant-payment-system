package br.kauan.spi.domain.entity.security;

import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;

public record AuthenticatedStatusReport(
        int sourceOrdinal,
        String authenticatedIspb,
        IncomingStatusReportCommand command
) {
}
