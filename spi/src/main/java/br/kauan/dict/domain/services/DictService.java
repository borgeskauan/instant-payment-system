package br.kauan.dict.domain.services;

import br.kauan.dict.domain.dtos.*;
import br.kauan.dict.port.input.DictUseCase;
import br.kauan.dict.port.output.DictRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DictService implements DictUseCase {

    private final DictRepository dictRepository;
    private final DictServiceMapper dictServiceMapper;

    public DictService(DictRepository dictRepository, DictServiceMapper dictServiceMapper) {
        this.dictRepository = dictRepository;
        this.dictServiceMapper = dictServiceMapper;
    }

    @Override
    public PixResponse buscarChavePix(String chavePix) {
        return dictRepository.buscarChavePix(chavePix);
    }

    @Override
    public PixResponse createPixKey(PixKeyCreationRequest request) {
        var internalRequest = convertToInternalRequest(request);

        validarCreationRequest(internalRequest);

        return dictRepository.createPixKey(internalRequest);
    }

    private void validarCreationRequest(PixKeyCreationInternalRequest request) {
        if (request.getKey() == null || request.getKey().isEmpty()) {
            throw new IllegalArgumentException("Chave Pix não pode ser nula ou vazia");
        }

        if (request.getKeyType() == null) {
            throw new IllegalArgumentException("Tipo da chave Pix não pode ser nulo ou vazio");
        }

        if (request.getKeyType().equals(PixKeyType.CPF) && !ValidadorCpfCnpj.isCpfValido(request.getKey())) {
            throw new IllegalArgumentException("Chave Pix inválida para o tipo CPF: " + request.getKey());
        }

        if (request.getKeyType().equals(PixKeyType.CNPJ) && !ValidadorCpfCnpj.isCnpjValido(request.getKey())) {
            throw new IllegalArgumentException("Chave Pix inválida para o tipo CNPJ: " + request.getKey());
        }

        verificarSeChaveJaExiste(request.getKey());
    }

    private PixKeyCreationInternalRequest convertToInternalRequest(PixKeyCreationRequest request) {
        var internalRequest = dictServiceMapper.toInternalRequest(request);

        return internalRequest
                .withCreationDate(Instant.now())
                .withKeyOwnershipDate(Instant.now());
    }

    private void verificarSeChaveJaExiste(String key) {
        if (dictRepository.buscarChavePix(key) != null) {
            throw new IllegalArgumentException("Chave Pix já existe: " + key);
        }
    }
}
