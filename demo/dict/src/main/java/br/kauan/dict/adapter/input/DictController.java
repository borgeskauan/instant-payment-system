package br.kauan.dict.adapter.input;

import br.kauan.dict.domain.dtos.PixKeyCreationRequest;
import br.kauan.dict.domain.dtos.PixResponse;
import br.kauan.dict.domain.services.DictService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/keys")
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    @GetMapping("/{pixKey}")
    public PixResponse getPixKey(@PathVariable String pixKey) {
        return dictService.resolve(pixKey);
    }

    @PostMapping
    public PixResponse createPixKey(@RequestBody PixKeyCreationRequest request) {
        return dictService.register(request);
    }
}
