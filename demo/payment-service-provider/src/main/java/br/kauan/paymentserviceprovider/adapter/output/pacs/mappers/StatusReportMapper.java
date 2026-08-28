package br.kauan.paymentserviceprovider.adapter.output.pacs.mappers;

import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.CommonsMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.GroupHeader;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.*;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.ErrorReason;
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
