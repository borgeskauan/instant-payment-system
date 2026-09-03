package br.kauan.dict.domain.services;

import br.kauan.dict.domain.dtos.Account;
import br.kauan.dict.domain.dtos.Owner;
import br.kauan.dict.domain.dtos.PixKeyCreationRequest;
import br.kauan.dict.domain.dtos.PixResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DictService {

    private final Map<String, PixResponse> entries = new ConcurrentHashMap<>();

    public PixResponse resolve(String pixKey) {
        PixResponse response = entries.get(pixKey);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PIX key not found");
        }
        return response;
    }

    public PixResponse register(PixKeyCreationRequest request) {
        validate(request);
        PixResponse response = new PixResponse(request.key(), request.account(), request.owner());
        if (entries.putIfAbsent(request.key(), response) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PIX key already exists");
        }
        return response;
    }

    private void validate(PixKeyCreationRequest request) {
        if (request == null) {
            throw badRequest("Request is required");
        }
        requireText(request.key(), "PIX key");

        Account account = request.account();
        if (account == null) {
            throw badRequest("Account is required");
        }
        requireText(account.participant(), "Account participant");
        requireText(account.branch(), "Account branch");
        requireText(account.number(), "Account number");
        requireText(account.type(), "Account type");

        Owner owner = request.owner();
        if (owner == null) {
            throw badRequest("Owner is required");
        }
        requireText(owner.taxIdNumber(), "Owner tax ID");
        requireText(owner.name(), "Owner name");
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
