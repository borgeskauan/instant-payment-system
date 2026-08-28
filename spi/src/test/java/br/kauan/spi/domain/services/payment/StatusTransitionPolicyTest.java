package br.kauan.spi.domain.services.payment;

import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.ClassificationType;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.Decision;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.LockedPayment;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.PreparedBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatusTransitionPolicyTest {

    private final StatusTransitionPolicy policy = new StatusTransitionPolicy();

    @Test
    void collapsesIdenticalReportsButPreservesTheirOriginalOrdinals() {
        AuthenticatedStatusReport first = report(0, "22222222", StatusReportOutcome.ACCEPTED);
        AuthenticatedStatusReport replay = report(1, "22222222", StatusReportOutcome.ACCEPTED);

        PreparedBatch prepared = policy.prepare(List.of(first, replay));

        assertThat(prepared.candidatesToClassify()).hasSize(1);
        assertThat(prepared.originalOrdinalsByRepresentative()).containsEntry(0, List.of(0, 1));
    }

    @Test
    void createsOneSettlementTransitionForAWaitingPayment() {
        AuthenticatedStatusReport report = report(0, "22222222", StatusReportOutcome.ACCEPTED);
        PreparedBatch prepared = policy.prepare(List.of(report));

        Decision decision = policy.decide(
                prepared.candidatesToClassify(),
                List.of(locked(report, PaymentState.WAITING_ACCEPTANCE, List.of()))
        );

        assertThat(decision.classifications()).isEmpty();
        assertThat(decision.transitions()).singleElement()
                .satisfies(transition -> assertThat(transition.resultingState()).isEqualTo(PaymentState.SETTLED));
    }

    @Test
    void treatsAnIdenticalTerminalReplayAsANoOpAndAContradictionAsAConflict() {
        AuthenticatedStatusReport accepted = report(0, "22222222", StatusReportOutcome.ACCEPTED);
        PreparedBatch acceptedBatch = policy.prepare(List.of(accepted));
        Decision replay = policy.decide(
                acceptedBatch.candidatesToClassify(),
                List.of(locked(accepted, PaymentState.SETTLED, List.of()))
        );

        AuthenticatedStatusReport rejected = report(1, "22222222", StatusReportOutcome.REJECTED);
        PreparedBatch rejectedBatch = policy.prepare(List.of(rejected));
        Decision contradiction = policy.decide(
                rejectedBatch.candidatesToClassify(),
                List.of(locked(rejected, PaymentState.SETTLED, List.of()))
        );

        assertThat(replay.classifications()).isEmpty();
        assertThat(replay.transitions()).isEmpty();
        assertThat(contradiction.classifications()).singleElement()
                .satisfies(classification -> assertThat(classification.type())
                        .isEqualTo(ClassificationType.STATUS_REPORT_CONFLICT));
    }

    @Test
    void distinguishesUnknownAndUnauthorizedReports() {
        AuthenticatedStatusReport unknown = report(0, "22222222", StatusReportOutcome.ACCEPTED);
        AuthenticatedStatusReport unauthorized = report(1, "33333333", StatusReportOutcome.ACCEPTED);
        PreparedBatch prepared = policy.prepare(List.of(unknown, unauthorized));

        Decision decision = policy.decide(
                prepared.candidatesToClassify(),
                List.of(locked(unauthorized, PaymentState.WAITING_ACCEPTANCE, List.of()))
        );

        assertThat(decision.classifications()).extracting(classification -> classification.type())
                .containsExactly(ClassificationType.STATUS_REPORT_CONFLICT, ClassificationType.UNAUTHORIZED_PSP);
    }

    private AuthenticatedStatusReport report(int ordinal, String authenticatedIspb, StatusReportOutcome outcome) {
        List<StatusReasonCode> reasonCodes = outcome == StatusReportOutcome.REJECTED
                ? List.of(StatusReasonCode.of("AM04"))
                : List.of();
        return new AuthenticatedStatusReport(
                ordinal,
                authenticatedIspb,
                new IncomingStatusReportCommand("P1", outcome, reasonCodes)
        );
    }

    private LockedPayment locked(
            AuthenticatedStatusReport report,
            PaymentState existingState,
            List<StatusReasonCode> existingReasonCodes
    ) {
        return new LockedPayment(
                report.sourceOrdinal(),
                report.command().originalPaymentId(),
                report.command().outcome(),
                report.command().reasonCodes(),
                report.authenticatedIspb(),
                existingState,
                null,
                existingReasonCodes,
                100L,
                "11111111",
                "22222222"
        );
    }
}
