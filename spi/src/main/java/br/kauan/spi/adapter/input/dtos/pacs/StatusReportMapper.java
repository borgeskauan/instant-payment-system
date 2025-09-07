package br.kauan.spi.adapter.input.dtos.pacs;

import br.kauan.spi.adapter.input.dtos.pacs.pacs002.*;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.Reason;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.status.StatusUpdate;
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

    public FIToFIPaymentStatusReport toRegulatoryReport(StatusReport internalReport) {
        GroupHeader groupHeader = commonsMapper.createGroupHeader(internalReport.getReportDetails());
        List<PaymentTransactionInfo> transactionInfoList = mapStatusUpdatesToTransactionInfo(internalReport.getStatusUpdates());

        return FIToFIPaymentStatusReport.builder()
                .groupHeader(groupHeader)
                .transactionInfo(transactionInfoList)
                .build();
    }

    public StatusReport fromRegulatoryReport(FIToFIPaymentStatusReport regulatoryReport) {
        BatchDetails reportDetails = mapGroupHeaderToReportDetails(regulatoryReport.getGroupHeader());
        List<StatusUpdate> statusUpdates = mapTransactionInfosToStatusUpdates(regulatoryReport.getTransactionInfo());

        return StatusReport.builder()
                .reportDetails(reportDetails)
                .statusUpdates(statusUpdates)
                .build();
    }

    private BatchDetails mapGroupHeaderToReportDetails(GroupHeader groupHeader) {
        return BatchDetails.builder()
                .id(groupHeader.getMessageId())
                .createdAt(groupHeader.getCreationTimestamp().toGregorianCalendar().toInstant())
                .build();
    }

    private List<StatusUpdate> mapTransactionInfosToStatusUpdates(List<PaymentTransactionInfo> transactionInfos) {
        return transactionInfos.stream()
                .map(this::mapTransactionInfoToStatusUpdate)
                .toList();
    }

    private StatusUpdate mapTransactionInfoToStatusUpdate(PaymentTransactionInfo info) {
        PaymentStatus status = codeMapping.mapExternalStatusCodeToPaymentStatus(info.getStatus());
        List<Reason> reasons = mapStatusReasonInformationsToReasons(info.getStatusReasonInformations());

        return StatusUpdate.builder()
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

    private List<PaymentTransactionInfo> mapStatusUpdatesToTransactionInfo(List<StatusUpdate> statusUpdates) {
        return statusUpdates.stream()
                .map(this::mapStatusUpdateToTransactionInfo)
                .toList();
    }

    private PaymentTransactionInfo mapStatusUpdateToTransactionInfo(StatusUpdate statusUpdate) {
        ExternalPaymentTransactionStatusCode status = codeMapping.mapPaymentStatusToExternalStatusCode(statusUpdate.getStatus());
        List<StatusReasonInformation> statusReasonInformationList = mapReasonsToStatusReasonInformation(statusUpdate.getReasons());

        return PaymentTransactionInfo.builder()
                .originalMessageId(statusUpdate.getOriginalRequestId())
                .originalPaymentId(statusUpdate.getOriginalPaymentId())
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