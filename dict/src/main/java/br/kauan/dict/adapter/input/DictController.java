package br.kauan.dict.adapter.input;

import br.kauan.dict.domain.dtos.PixKeyCreationRequest;
import br.kauan.dict.domain.dtos.PixResponse;
import br.kauan.dict.port.input.DictUseCase;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/keys")
public class DictController {

    private final DictUseCase dictUseCase;

    public DictController(DictUseCase dictUseCase) {
        this.dictUseCase = dictUseCase;
    }

    @GetMapping("/{chavePix}")
    public PixResponse getChavePix(@PathVariable String chavePix) {
        return dictUseCase.buscarChavePix(chavePix);
    }

    @PostMapping
    public PixResponse createChavePix(@RequestBody PixKeyCreationRequest request) {
        return dictUseCase.createPixKey(request);
    }
}
