package br.kauan.spi.dtos.pacs;

import br.kauan.spi.domain.entity.status.*;
import br.kauan.spi.dtos.pacs.pacs002.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;

@Slf4j
@Service
public class StatusReportMapper {

    public FIToFIPaymentStatusReport toRegulatoryReport(StatusReport internalReport) {
        GroupHeaderStatusReport groupHeader = createGroupHeader(internalReport.getReportDetails());
        List<PaymentTransactionInfo> transactionInfoList = internalReport.getStatusUpdates().stream()
                .map(this::toPaymentTransactionInfo)
                .toList();

        return FIToFIPaymentStatusReport.builder()
                .groupHeader(groupHeader)
                .transactionInfo(transactionInfoList)
                .build();
    }

    private static GroupHeaderStatusReport createGroupHeader(ReportDetails reportDetails) {
        var xmlTimestamp = convertInstantToXmlGregorianCalendar(reportDetails.getGeneratedAt());

        return GroupHeaderStatusReport.builder()
                .messageId(reportDetails.getReportId())
                .creationTimestamp(xmlTimestamp)
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

    StatusReport fromRegulatoryReport(FIToFIPaymentStatusReport regulatoryReport) {
        GroupHeaderStatusReport groupHeader = regulatoryReport.getGroupHeader();
        List<PaymentTransactionInfo> transactionInfos = regulatoryReport.getTransactionInfo();

        // Map group header to ReportDetails
        ReportDetails reportDetails = ReportDetails.builder()
                .reportId(groupHeader.getMessageId())
                .generatedAt(groupHeader.getCreationTimestamp().toGregorianCalendar().toInstant())
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
                            .description(reasonInfo.getAdditionalInformation().isEmpty() ? "" : reasonInfo.getAdditionalInformation().get(0))
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

    private static XMLGregorianCalendar convertInstantToXmlGregorianCalendar(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);

        // Create a GregorianCalendar from the ZonedDateTime
        GregorianCalendar gregorianCalendar = GregorianCalendar.from(zdt);

        // Create the XMLGregorianCalendar
        try {
            return DatatypeFactory.newInstance()
                    .newXMLGregorianCalendar(gregorianCalendar);
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}