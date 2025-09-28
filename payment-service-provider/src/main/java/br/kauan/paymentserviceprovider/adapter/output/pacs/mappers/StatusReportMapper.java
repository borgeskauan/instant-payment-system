package br.kauan.paymentserviceprovider.adapter.output.pacs.mappers;

import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.CommonsMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.GroupHeader;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.*;
import br.kauan.paymentserviceprovider.domain.entity.commons.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.ErrorReason;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
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
        List<ErrorReason> errorReasons = mapStatusReasonInformationsToReasons(info.getStatusReasonInformations());

        return StatusReport.builder()
                .originalPaymentId(info.getOriginalPaymentId())
                .status(status)
                .errorReasons(errorReasons)
                .build();
    }

    private List<ErrorReason> mapStatusReasonInformationsToReasons(List<StatusReasonInformation> reasonInformations) {
        if (reasonInformations == null) {
            return List.of();
        }

        return reasonInformations.stream()
                .map(this::mapStatusReasonInformationToReason)
                .toList();
    }

    private ErrorReason mapStatusReasonInformationToReason(StatusReasonInformation reasonInfo) {
        return ErrorReason.builder()
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
        List<StatusReasonInformation> statusReasonInformationList = mapReasonsToStatusReasonInformation(statusReport.getErrorReasons());

        return PaymentTransactionInfo.builder()
                .originalPaymentId(statusReport.getOriginalPaymentId())
                .status(status)
                .statusReasonInformations(statusReasonInformationList)
                .build();
    }

    private List<StatusReasonInformation> mapReasonsToStatusReasonInformation(List<ErrorReason> errorReasons) {
        if (errorReasons == null) {
            return Collections.emptyList();
        }

        return errorReasons.stream()
                .map(this::mapReasonToStatusReasonInformation)
                .toList();
    }

    private StatusReasonInformation mapReasonToStatusReasonInformation(ErrorReason errorReason) {
        var mappedCode = codeMapping.mapReasonCodeToExternalStatusReasonCode(errorReason.getErrorCode());

        return StatusReasonInformation.builder()
                .reason(StatusReason.builder()
                        .code(mappedCode)
                        .build())
                .additionalInformation(errorReason.getDescriptions())
                .build();
    }
}