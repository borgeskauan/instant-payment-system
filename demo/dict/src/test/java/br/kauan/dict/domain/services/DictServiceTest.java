package br.kauan.dict.domain.services;

import br.kauan.dict.domain.dtos.Account;
import br.kauan.dict.domain.dtos.Owner;
import br.kauan.dict.domain.dtos.PixKeyCreationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictServiceTest {

    private final DictService service = new DictService();

    @Test
    void registersAndResolvesOneRecipientByExactKey() {
        PixKeyCreationRequest request = request("bob@example.com");

        assertThat(service.register(request)).isEqualTo(service.resolve("bob@example.com"));
    }

    @Test
    void rejectsASecondRecipientForTheSameKey() {
        service.register(request("bob@example.com"));

        assertThatThrownBy(() -> service.register(request("bob@example.com")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void reportsAnUnknownKeyAsNotFound() {
        assertThatThrownBy(() -> service.resolve("missing@example.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejectsAnIncompleteDirectoryEntry() {
        assertThatThrownBy(() -> service.register(new PixKeyCreationRequest("", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private PixKeyCreationRequest request(String key) {
        return new PixKeyCreationRequest(
                key,
                new Account("22222222", "0001", "12345678", "CHECKING"),
                new Owner("22222222222", "Bob")
        );
    }
}
