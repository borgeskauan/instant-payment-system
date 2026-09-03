package br.kauan.paymentserviceprovider.adapter.output.pacs.mappers;

import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.CommonsMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.GroupHeader;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.*;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatusReportMapper {

    private final CommonsMapper commonsMapper;
    private final CodeMapping codeMapping;

    public FIToFIPaymentStatusReport toRegulatoryReport(StatusReport statusReport) {
        return toRegulatoryReport(List.of(statusReport));
    }

    public FIToFIPaymentStatusReport toRegulatoryReport(List<StatusReport> statusReports) {
        GroupHeader groupHeader = commonsMapper.createGroupHeader(statusReports.size());
        List<PaymentTransactionInfo> transactionInfoList = mapStatusUpdatesToTransactionInfos(statusReports);

        return FIToFIPaymentStatusReport.builder()
                .groupHeader(groupHeader)
                .transactionInfo(transactionInfoList)
                .build();
    }

    private List<PaymentTransactionInfo> mapStatusUpdatesToTransactionInfos(List<StatusReport> statusReports) {
        var transactionInfoList = new java.util.ArrayList<PaymentTransactionInfo>(statusReports.size());
        for (StatusReport statusReport : statusReports) {
            transactionInfoList.add(mapStatusUpdateToTransactionInfo(statusReport));
        }
        return transactionInfoList;
    }

    public List<StatusReport> fromRegulatoryReport(FIToFIPaymentStatusReport regulatoryReport) {
        return mapTransactionInfosToStatusUpdates(regulatoryReport.getTransactionInfo());
    }

    private List<StatusReport> mapTransactionInfosToStatusUpdates(List<PaymentTransactionInfo> transactionInfos) {
        return transactionInfos.stream()
                .map(this::mapTransactionInfoToStatusUpdate)
                .toList();
    }

    private StatusReport mapTransactionInfoToStatusUpdate(PaymentTransactionInfo info) {
        return StatusReport.builder()
                .originalPaymentId(info.getOriginalPaymentId())
                .status(codeMapping.mapExternalStatusCodeToPaymentStatus(info.getStatus()))
                .build();
    }

    private PaymentTransactionInfo mapStatusUpdateToTransactionInfo(StatusReport statusReport) {
        return PaymentTransactionInfo.builder()
                .originalPaymentId(statusReport.getOriginalPaymentId())
                .status(codeMapping.mapPaymentStatusToExternalStatusCode(statusReport.getStatus()))
                .statusReasonInformations(List.of())
                .build();
    }
}
