package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.status.StatusReport;

public interface StatusReportUseCase {
    void processStatusReport(String ispb, StatusReport status);
}
