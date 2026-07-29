package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;

import java.util.List;

public record StatusReportProcessingResult(
        List<AuthenticatedStatusReport> divergentStatusReports,
        List<AuthenticatedStatusReport> unauthorizedStatusReports
) {
}
