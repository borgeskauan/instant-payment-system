# Reavaliar custos do load-tool após desacoplar o relatório

- [x] Superseded pela reescrita greenfield do load-tool em Rust

## Por que foi superseded

Esta task descrevia custos internos da implementação Go, incluindo mutexes globais, milhares de goroutines e serialização de CSV no caminho de geração.

O gerador ativo foi refeito em Rust com pacing e networking separados, deadlines sem catch-up, estado pré-alocado e reporting fora da fronteira do hot path. A implementação Go foi removida e não existe mais uma base útil sobre a qual executar as otimizações propostas aqui.

A comparação quantitativa entre a última implementação Go e o gerador Rust foi concluída em [`comparar-load-tool-go-rust.md`](../comparar-load-tool-go-rust.md).
