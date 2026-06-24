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

import java.util.ArrayList;
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
                .createdAt(PacsDateTime.toInstant(groupHeader.getCreationTimestamp()))
                .build();
    }

    private List<StatusReport> mapTransactionInfosToStatusUpdates(List<PaymentTransactionInfo> transactionInfos) {
        var statusReports = new ArrayList<StatusReport>(transactionInfos.size());
        for (PaymentTransactionInfo transactionInfo : transactionInfos) {
            statusReports.add(mapTransactionInfoToStatusUpdate(transactionInfo));
        }
        return statusReports;
    }

    private StatusReport mapTransactionInfoToStatusUpdate(PaymentTransactionInfo info) {
        PaymentStatus status = codeMapping.mapExternalStatusCodeToPaymentStatus(info.getStatus());
        List<Reason> reasons = mapStatusReasonInformationsToReasons(info.getStatusReasonInformations());

        return StatusReport.builder()
                .originalPaymentId(info.getOriginalPaymentId())
                .status(status)
                .reasons(reasons)
                .build();
    }

    private List<Reason> mapStatusReasonInformationsToReasons(List<StatusReasonInformation> reasonInformations) {
        if (reasonInformations == null) {
            return List.of();
        }

        var reasons = new ArrayList<Reason>(reasonInformations.size());
        for (StatusReasonInformation reasonInformation : reasonInformations) {
            reasons.add(mapStatusReasonInformationToReason(reasonInformation));
        }
        return reasons;
    }

    private Reason mapStatusReasonInformationToReason(StatusReasonInformation reasonInfo) {
        return Reason.builder()
                .descriptions(reasonInfo.getAdditionalInformation())
                .build();
    }

    private List<PaymentTransactionInfo> mapStatusUpdatesToTransactionInfo(List<StatusReport> statusReports) {
        var transactionInfo = new ArrayList<PaymentTransactionInfo>(statusReports.size());
        for (StatusReport statusReport : statusReports) {
            transactionInfo.add(mapStatusUpdateToTransactionInfo(statusReport));
        }
        return transactionInfo;
    }

    private PaymentTransactionInfo mapStatusUpdateToTransactionInfo(StatusReport statusReport) {
        ExternalPaymentTransactionStatusCode status = codeMapping.mapPaymentStatusToExternalStatusCode(statusReport.getStatus());
        List<StatusReasonInformation> statusReasonInformationList = mapReasonsToStatusReasonInformation(statusReport.getReasons());

        return PaymentTransactionInfo.builder()
                .originalPaymentId(statusReport.getOriginalPaymentId())
                .status(status)
                .statusReasonInformations(statusReasonInformationList)
                .build();
    }

    private List<StatusReasonInformation> mapReasonsToStatusReasonInformation(List<Reason> reasons) {
        if (reasons == null) {
            return Collections.emptyList();
        }

        var statusReasonInformation = new ArrayList<StatusReasonInformation>(reasons.size());
        for (Reason reason : reasons) {
            statusReasonInformation.add(mapReasonToStatusReasonInformation(reason));
        }
        return statusReasonInformation;
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
