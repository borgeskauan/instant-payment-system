package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaAdapterTest {

    @Test
    void adapterDoesNotExposeSingleTransactionSave() {
        assertThrows(NoSuchMethodException.class,
                () -> JpaAdapter.class.getMethod(
                        "saveTransaction",
                        PaymentTransactionCommand.class,
                        PaymentStatus.class
                ));
    }

    @Test
    void adapterDoesNotExposeSingleStatusUpdate() {
        assertThrows(NoSuchMethodException.class,
                () -> JpaAdapter.class.getMethod(
                        "updateStatus",
                        String.class,
                        PaymentStatus.class
                ));
    }

    @Test
    void adapterDoesNotExposeBlindBatchStatusUpdate() {
        assertThrows(NoSuchMethodException.class,
                () -> JpaAdapter.class.getMethod(
                        "updateStatuses",
                        List.class,
                        PaymentStatus.class
                ));
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsPersistsOnlySettlementFieldsAndFingerprintUsingOneSqlQuery()
            throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction("E2E-2", "33333333", "44444444");

        Connection connection = stubPaymentExecute(
                jdbcTemplate,
                new PaymentAction(0, "ACCEPTANCE_REQUEST"),
                new PaymentAction(1, "ACCEPTANCE_REQUEST")
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, second));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(any(ConnectionCallback.class));
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("FROM unnest(")
                .contains("payment_id")
                .contains("amount_cents")
                .contains("status")
                .contains("sender_bank_code")
                .contains("receiver_bank_code")
                .contains("request_fingerprint")
                .contains("request_fingerprint_version")
                .contains("ON CONFLICT (payment_id) DO NOTHING")
                .doesNotContain("VALUES")
                .doesNotContain("COUNT(DISTINCT (request_fingerprint_version, request_fingerprint))")
                .doesNotContain("sender_name")
                .doesNotContain("sender_tax_id")
                .doesNotContain("sender_pix_key")
                .doesNotContain("sender_account_number")
                .doesNotContain("receiver_name")
                .doesNotContain("receiver_tax_id")
                .doesNotContain("receiver_pix_key")
                .doesNotContain("receiver_account_number")
                .doesNotContain("currency")
                .doesNotContain("description");
        assertThat(result.acceptanceRequests()).containsExactly(first, second);
        assertThat(result.divergentDuplicates()).isEmpty();
        verify(jdbcTemplate, never()).batchUpdate(
                anyString(),
                org.mockito.ArgumentMatchers.<List<PaymentTransactionCommand>>any(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class)
        );
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsUsesStableArraySqlForBatchInputs() {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        try {
            adapter.storeAndClassifyIncomingPaymentRequests(List.of(
                    paymentTransaction("E2E-1", "11111111", "22222222"),
                    paymentTransaction("E2E-2", "33333333", "44444444")
            ));
        } catch (EmptyResultDataAccessException ignored) {
            // The mocked callback stops execution after proving the stable JDBC path is used.
        }

        verify(jdbcTemplate).execute(any(ConnectionCallback.class));
        verify(jdbcTemplate, never()).query(
                anyString(),
                any(PreparedStatementSetter.class),
                any(RowMapper.class)
        );
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsSkipsSqlForSameBatchDivergentPaymentId() {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction("E2E-1", "33333333", "22222222");

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, second));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(first, second);
        verify(jdbcTemplate, never()).query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsSendsOneLogicalSqlRowForSameBatchIdenticalPaymentId()
            throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand repeated = paymentTransaction("E2E-1", "11111111", "22222222");
        Connection connection = stubPaymentExecute(
                jdbcTemplate,
                new PaymentAction(0, "ACCEPTANCE_REQUEST")
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, repeated));

        ArgumentCaptor<Object[]> ordinalsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(connection).createArrayOf(eq("int4"), ordinalsCaptor.capture());
        assertThat(ordinalsCaptor.getValue()).containsExactly(0);
        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.divergentDuplicates()).isEmpty();
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsExpandsPersistedDivergenceToEveryOriginalSameBatchRecord()
            throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand repeated = paymentTransaction("E2E-1", "11111111", "22222222");
        stubPaymentExecute(
                jdbcTemplate,
                new PaymentAction(0, "DIVERGENT_DUPLICATE")
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, repeated));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(first, repeated);
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsKeepsDivergentDuplicatesInOriginalBatchOrder()
            throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand existingDivergent = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand sameBatchDivergent = paymentTransaction("E2E-2", "33333333", "44444444");
        PaymentTransactionCommand existingDivergentRepeated = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand sameBatchDivergentRepeated = paymentTransaction("E2E-2", "55555555", "44444444");
        stubPaymentExecute(
                jdbcTemplate,
                new PaymentAction(0, "DIVERGENT_DUPLICATE")
        );

        PaymentTransactionPersistenceResult result = adapter.storeAndClassifyIncomingPaymentRequests(List.of(
                existingDivergent,
                sameBatchDivergent,
                existingDivergentRepeated,
                sameBatchDivergentRepeated
        ));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(
                existingDivergent,
                sameBatchDivergent,
                existingDivergentRepeated,
                sameBatchDivergentRepeated
        );
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsMapsSqlActionsBackToOriginalCommandsByOrdinal() throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction("E2E-2", "33333333", "44444444");
        stubPaymentExecute(
                jdbcTemplate,
                new PaymentAction(1, "DIVERGENT_DUPLICATE"),
                new PaymentAction(0, "ACCEPTANCE_REQUEST")
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, second));

        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.divergentDuplicates()).containsExactly(second);
    }

    @Test
    void classifyAndApplyIncomingStatusReportsSendsOneLogicalSqlRowForSameBatchIdenticalPaymentId()
            throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        StatusReportCommand first = statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        StatusReportCommand repeated = statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        Connection connection = stubStatusExecute(
                jdbcTemplate,
                new StatusAction(0, "SETTLED_PAYMENT", "E2E-1", 1000L, "11111111", "22222222")
        );

        StatusReportPersistenceResult result =
                adapter.classifyAndApplyIncomingStatusReports(List.of(first, repeated));

        ArgumentCaptor<Object[]> ordinalsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(connection).createArrayOf(eq("int4"), ordinalsCaptor.capture());
        assertThat(ordinalsCaptor.getValue()).containsExactly(0);
        assertThat(result.settledPayments())
                .extracting(PaymentTransactionCommand::getPaymentId)
                .containsExactly("E2E-1");
        assertThat(result.divergentStatusReports()).isEmpty();
    }

    @Test
    void classifyAndApplyIncomingStatusReportsUsesStableArraySqlForBatchInputs() throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        Connection connection = stubStatusExecute(jdbcTemplate);

        adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS),
                statusReport("E2E-2", PaymentStatus.REJECTED)
        ));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(any(ConnectionCallback.class));
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("FROM unnest(")
                .contains("?::int[]")
                .contains("?::text[]")
                .contains("ORDER BY p.payment_id")
                .contains("FOR UPDATE OF p")
                .contains("ranked AS")
                .contains("ORDER BY ordinal")
                .doesNotContain("VALUES")
                .doesNotContain("ORDER BY i.ordinal")
                .doesNotContain("fast_path_settleable");
        verify(jdbcTemplate, never()).query(
                anyString(),
                any(PreparedStatementSetter.class),
                any(RowMapper.class)
        );
    }

    @Test
    void classifyAndApplyIncomingStatusReportsSkipsSqlForSameBatchConflictingStatuses() {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        StatusReportCommand accepted = statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        StatusReportCommand rejected = statusReport("E2E-1", PaymentStatus.REJECTED);

        StatusReportPersistenceResult result =
                adapter.classifyAndApplyIncomingStatusReports(List.of(accepted, rejected));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(accepted, rejected);
        verify(jdbcTemplate, never()).query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));
    }

    @Test
    void classifyAndApplyIncomingStatusReportsExpandsPersistedDivergenceToEveryOriginalSameBatchRecord()
            throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        StatusReportCommand first = statusReport("E2E-1", PaymentStatus.REJECTED);
        StatusReportCommand repeated = statusReport("E2E-1", PaymentStatus.REJECTED);
        stubStatusExecute(
                jdbcTemplate,
                new StatusAction(0, "DIVERGENT_STATUS_REPORT", "E2E-1")
        );

        StatusReportPersistenceResult result =
                adapter.classifyAndApplyIncomingStatusReports(List.of(first, repeated));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(first, repeated);
    }

    @Test
    void classifyAndApplyIncomingStatusReportsKeepsDivergentReportsInOriginalBatchOrder()
            throws Exception {
        Mapper repositoryMapper = new Mapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JpaAdapter adapter = new JpaAdapter(
                repositoryMapper,
                jdbcTemplate
        );
        StatusReportCommand existingDivergent = statusReport("E2E-1", PaymentStatus.REJECTED);
        StatusReportCommand sameBatchDivergent = statusReport("E2E-2", PaymentStatus.ACCEPTED_IN_PROCESS);
        StatusReportCommand existingDivergentRepeated = statusReport("E2E-1", PaymentStatus.REJECTED);
        StatusReportCommand sameBatchDivergentRepeated = statusReport("E2E-2", PaymentStatus.REJECTED);
        stubStatusExecute(
                jdbcTemplate,
                new StatusAction(0, "DIVERGENT_STATUS_REPORT", "E2E-1")
        );

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                existingDivergent,
                sameBatchDivergent,
                existingDivergentRepeated,
                sameBatchDivergentRepeated
        ));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(
                existingDivergent,
                sameBatchDivergent,
                existingDivergentRepeated,
                sameBatchDivergentRepeated
        );
    }

    private static Connection stubPaymentExecute(JdbcTemplate jdbcTemplate, PaymentAction... rows) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Array sqlArray = mock(Array.class);
        when(connection.createArrayOf(anyString(), any(Object[].class))).thenReturn(sqlArray);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);

        AtomicInteger rowIndex = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(ignored -> rowIndex.incrementAndGet() < rows.length);
        when(resultSet.getInt(1)).thenAnswer(ignored -> rows[rowIndex.get()].ordinal());
        when(resultSet.getInt("ordinal")).thenAnswer(ignored -> rows[rowIndex.get()].ordinal());
        when(resultSet.getString(2)).thenAnswer(ignored -> rows[rowIndex.get()].action());
        when(resultSet.getString("action")).thenAnswer(ignored -> rows[rowIndex.get()].action());
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        return connection;
    }

    private static Connection stubStatusExecute(JdbcTemplate jdbcTemplate, StatusAction... rows) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Array sqlArray = mock(Array.class);
        when(connection.createArrayOf(anyString(), any(Object[].class))).thenReturn(sqlArray);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);

        AtomicInteger rowIndex = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(ignored -> rowIndex.incrementAndGet() < rows.length);
        when(resultSet.getInt(1)).thenAnswer(ignored -> rows[rowIndex.get()].ordinal());
        when(resultSet.getInt("ordinal")).thenAnswer(ignored -> rows[rowIndex.get()].ordinal());
        when(resultSet.getString(2)).thenAnswer(ignored -> rows[rowIndex.get()].action());
        when(resultSet.getString("action")).thenAnswer(ignored -> rows[rowIndex.get()].action());
        when(resultSet.getString(3)).thenAnswer(ignored -> rows[rowIndex.get()].paymentId());
        when(resultSet.getString("payment_id")).thenAnswer(ignored -> rows[rowIndex.get()].paymentId());
        when(resultSet.getObject(4, Long.class)).thenAnswer(ignored -> rows[rowIndex.get()].amountCents());
        when(resultSet.getObject("amount_cents", Long.class)).thenAnswer(ignored -> rows[rowIndex.get()].amountCents());
        when(resultSet.getString(5)).thenAnswer(ignored -> rows[rowIndex.get()].senderBankCode());
        when(resultSet.getString("sender_bank_code")).thenAnswer(ignored -> rows[rowIndex.get()].senderBankCode());
        when(resultSet.getString(6)).thenAnswer(ignored -> rows[rowIndex.get()].receiverBankCode());
        when(resultSet.getString("receiver_bank_code")).thenAnswer(ignored -> rows[rowIndex.get()].receiverBankCode());
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        return connection;
    }

    private record PaymentAction(int ordinal, String action) {
    }

    private record StatusAction(
            int ordinal,
            String action,
            String paymentId,
            Long amountCents,
            String senderBankCode,
            String receiverBankCode
    ) {
        private StatusAction(int ordinal, String action, String paymentId) {
            this(ordinal, action, paymentId, null, null, null);
        }
    }

    @Test
    void mapperBuildsPartiesWhenOnlyBankCodesAreAvailable() {
        Entity entity = new Entity();
        entity.setPaymentId("E2E-1");
        entity.setAmountCents(1000L);
        entity.setSenderBankCode("11111111");
        entity.setReceiverBankCode("22222222");

        PaymentTransactionCommand transaction = new Mapper().toDomain(entity);

        assertThat(transaction.getSender().getAccount().getBankCode()).isEqualTo("11111111");
        assertThat(transaction.getReceiver().getAccount().getBankCode()).isEqualTo("22222222");
    }

    private static PaymentTransactionCommand paymentTransaction() {
        return paymentTransaction("E2E-1", "11111111", "22222222");
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .currency("BRL")
                .description("test")
                .sender(party(senderBankCode))
                .receiver(party(receiverBankCode))
                .build();
    }

    private static StatusReportCommand statusReport(String paymentId, PaymentStatus status) {
        return StatusReportCommand.builder()
                .originalPaymentId(paymentId)
                .status(status)
                .build();
    }

    private static Party party(String bankCode) {
        return Party.builder()
                .name("Name")
                .taxId("123")
                .pixKey("pix-" + bankCode)
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
