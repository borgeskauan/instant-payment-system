package br.kauan.spi.domain.entity.status;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusReasonCodeTest {

    @Test
    void normalizesReasonCodesAsAStableSemanticSet() {
        assertThat(StatusReasonCode.normalize(List.of(
                StatusReasonCode.of(" am04 "),
                StatusReasonCode.of("AB03"),
                StatusReasonCode.of("AM04")
        ))).extracting(StatusReasonCode::value).containsExactly("AB03", "AM04");
    }

    @Test
    void rejectsBlankOversizedOrNonStandardReasonCodes() {
        assertThatThrownBy(() -> StatusReasonCode.of(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatusReasonCode.of("ABCDE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatusReasonCode.of("A-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
