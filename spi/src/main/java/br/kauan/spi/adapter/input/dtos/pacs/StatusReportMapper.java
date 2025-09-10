package br.kauan.spi.adapter.input.dtos.pacs;

import br.kauan.spi.adapter.input.dtos.pacs.pacs002.*;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.Reason;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
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

    public FIToFIPaymentStatusReport toRegulatoryReport(StatusBatch internalReport) {
        GroupHeader groupHeader = commonsMapper.createGroupHeader(internalReport.getBatchDetails());
        List<PaymentTransactionInfo> transactionInfoList = mapStatusUpdatesToTransactionInfo(internalReport.getStatusReports());

        return FIToFIPaymentStatusReport.builder()
                .groupHeader(groupHeader)
                .transactionInfo(transactionInfoList)
                .build();
    }

    public StatusBatch fromRegulatoryReport(FIToFIPaymentStatusReport regulatoryReport) {
        BatchDetails reportDetails = mapGroupHeaderToReportDetails(regulatoryReport.getGroupHeader());
        List<StatusReport> statusReports = mapTransactionInfosToStatusUpdates(regulatoryReport.getTransactionInfo());

        return StatusBatch.builder()
                .batchDetails(reportDetails)
                .statusReports(statusReports)
                .build();
    }

    private BatchDetails mapGroupHeaderToReportDetails(GroupHeader groupHeader) {
        return BatchDetails.builder()
                .id(groupHeader.getMessageId())
                .createdAt(groupHeader.getCreationTimestamp().toGregorianCalendar().toInstant())
                .build();
    }

    private List<StatusReport> mapTransactionInfosToStatusUpdates(List<PaymentTransactionInfo> transactionInfos) {
        return transactionInfos.stream()
                .map(this::mapTransactionInfoToStatusUpdate)
                .toList();
    }

    private StatusReport mapTransactionInfoToStatusUpdate(PaymentTransactionInfo info) {
        PaymentStatus status = codeMapping.mapExternalStatusCodeToPaymentStatus(info.getStatus());
        List<Reason> reasons = mapStatusReasonInformationsToReasons(info.getStatusReasonInformations());

        return StatusReport.builder()
                .originalRequestId(info.getOriginalMessageId())
                .originalPaymentId(info.getOriginalPaymentId())
                .status(status)
                .reasons(reasons)
                .build();
    }

    private List<Reason> mapStatusReasonInformationsToReasons(List<StatusReasonInformation> reasonInformations) {
        return reasonInformations.stream()
                .map(this::mapStatusReasonInformationToReason)
                .toList();
    }

    private Reason mapStatusReasonInformationToReason(StatusReasonInformation reasonInfo) {
        return Reason.builder()
                .descriptions(reasonInfo.getAdditionalInformation())
                .build();
    }

    private List<PaymentTransactionInfo> mapStatusUpdatesToTransactionInfo(List<StatusReport> statusReports) {
        return statusReports.stream()
                .map(this::mapStatusUpdateToTransactionInfo)
                .toList();
    }

    private PaymentTransactionInfo mapStatusUpdateToTransactionInfo(StatusReport statusReport) {
        ExternalPaymentTransactionStatusCode status = codeMapping.mapPaymentStatusToExternalStatusCode(statusReport.getStatus());
        List<StatusReasonInformation> statusReasonInformationList = mapReasonsToStatusReasonInformation(statusReport.getReasons());

        return PaymentTransactionInfo.builder()
                .originalMessageId(statusReport.getOriginalRequestId())
                .originalPaymentId(statusReport.getOriginalPaymentId())
                .status(status)
                .statusReasonInformations(statusReasonInformationList)
                .build();
    }

    private List<StatusReasonInformation> mapReasonsToStatusReasonInformation(List<Reason> reasons) {
        return reasons.stream()
                .map(this::mapReasonToStatusReasonInformation)
                .toList();
    }

    private StatusReasonInformation mapReasonToStatusReasonInformation(Reason reason) {
        var mappedCode = codeMapping.mapReasonCodeToExternalStatusReasonCode(reason.getCode());

        return StatusReasonInformation.builder()
                .reason(StatusReason.builder()
                        .code(mappedCode)
                        .build())
                .additionalInformation(reason.getDescriptions())
                .build();
    }
}