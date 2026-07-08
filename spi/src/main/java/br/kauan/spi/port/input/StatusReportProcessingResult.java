package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.status.StatusReportCommand;

import java.util.List;

public record StatusReportProcessingResult(
        List<StatusReportCommand> divergentStatusReports
) {
}
