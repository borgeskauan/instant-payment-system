package br.kauan.spi.dtos.pacs;

import br.kauan.spi.domain.entity.status.*;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.dtos.pacs.pacs002.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class StatusReportMapper {

    private final CommonsMapper commonsMapper;

    public StatusReportMapper(CommonsMapper commonsMapper) {
        this.commonsMapper = commonsMapper;
    }

    public FIToFIPaymentStatusReport toRegulatoryReport(StatusReport internalReport) {
        GroupHeader groupHeader = commonsMapper.createGroupHeader(internalReport.getReportDetails());
        List<PaymentTransactionInfo> transactionInfoList = internalReport.getStatusUpdates().stream()
                .map(this::toPaymentTransactionInfo)
                .toList();

        return FIToFIPaymentStatusReport.builder()
                .groupHeader(groupHeader)
                .transactionInfo(transactionInfoList)
                .build();
    }

    public StatusReport fromRegulatoryReport(FIToFIPaymentStatusReport regulatoryReport) {
        GroupHeader groupHeader = regulatoryReport.getGroupHeader();
        List<PaymentTransactionInfo> transactionInfos = regulatoryReport.getTransactionInfo();

        // Map group header to ReportDetails
        BatchDetails reportDetails = BatchDetails.builder()
                .id(groupHeader.getMessageId())
                .createdAt(groupHeader.getCreationTimestamp().toGregorianCalendar().toInstant())
                .build();

        // Map transaction infos to StatusUpdates
        List<StatusUpdate> statusUpdates = transactionInfos.stream().map(info -> {
            // Map status code to internal status
            PaymentStatus status = switch (info.getStatus()) {
                case ACCC -> PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER;
                case ACSC -> PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER;
                case ACSP -> PaymentStatus.ACCEPTED_IN_PROCESS;
                case RJCT -> PaymentStatus.REJECTED;
            };

            // Map status reason informations to reasons
            List<Reason> reasons = info.getStatusReasonInformations().stream()
                    .map(reasonInfo -> Reason.builder()
                            .description(reasonInfo.getAdditionalInformation().isEmpty() ? "" : reasonInfo.getAdditionalInformation().getFirst())
                            .build())
                    .toList();

            return StatusUpdate.builder()
                    .originalRequestId(info.getOriginalMessageId())
                    .originalPaymentId(info.getOriginalPaymentId())
                    .status(status)
                    .reasons(reasons)
                    .build();
        }).toList();

        return StatusReport.builder()
                .reportDetails(reportDetails)
                .statusUpdates(statusUpdates)
                .build();
    }

    private PaymentTransactionInfo toPaymentTransactionInfo(StatusUpdate statusUpdate) {
        var status = switch (statusUpdate.getStatus()) {
            case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> ExternalPaymentTransactionStatusCode.ACCC;
            case ACCEPTED_AND_SETTLED_FOR_SENDER -> ExternalPaymentTransactionStatusCode.ACSC;
            case ACCEPTED_IN_PROCESS -> ExternalPaymentTransactionStatusCode.ACSP;
            case REJECTED -> ExternalPaymentTransactionStatusCode.RJCT;
        };

        List<StatusReasonInformation> statusReasonInformationList = statusUpdate.getReasons().stream()
                .map(reason -> StatusReasonInformation.builder()
                        .reason(StatusReason.builder()
                                .code(ExternalStatusReasonCode.AB_03) // TODO: mapear corretamente
                                .build())
                        .additionalInformation(Collections.singletonList(reason.getDescription()))
                        .build())
                .toList();

        return PaymentTransactionInfo.builder()
                .originalMessageId(statusUpdate.getOriginalRequestId())
                .originalPaymentId(statusUpdate.getOriginalPaymentId())
                .status(status)
                .statusReasonInformations(statusReasonInformationList)
                .build();
    }
}