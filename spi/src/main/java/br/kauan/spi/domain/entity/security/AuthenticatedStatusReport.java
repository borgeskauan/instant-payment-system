package br.kauan.spi.domain.entity.security;

import br.kauan.spi.domain.entity.status.StatusReportCommand;

public record AuthenticatedStatusReport(
        int sourceOrdinal,
        String authenticatedIspb,
        StatusReportCommand command
) {
}
