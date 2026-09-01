# Archived documentation

This directory preserves the engineering history behind the current system. Its contents may describe superseded architecture, experimental terminology, intermediate measurements, or repository paths that no longer exist.

Nothing here is a source of current behavior. Use the [project overview](../../README.md), [system design](../design.md), and [performance evidence](../performance.md) as the canonical documentation.

## Curated engineering history

These documents preserve decisions that materially shaped the final system:

- [Participant balance and implicit reservation](architecture/reservation-based-participant-balance.md) explains why synthetic balance buckets were replaced by one available balance per participant.
- [Durable notification delivery through Kafka](architecture/kafka-durable-notification-delivery.md) records the transition from database-managed delivery state to Kafka as the operational delivery log.
- [SPI schema evolution](architecture/spi-schema-evolution.md) connects the experimental schemas to the compact baseline retained by the MVP.
- [Go and Rust load-tool comparison](architecture/load-tool-go-rust-comparison.md) explains why the qualifying generator was rebuilt around explicit pacing and ownership boundaries.
- [2,000 TPS stabilization](performance/2k-tps-stabilization.md) consolidates the final performance campaign.
- [Experimental findings](performance/experimental-findings.md) preserves intermediate results that changed a design or measurement decision.

The shorter [engineering evolution](../engineering-evolution.md) is the preferred entry point for this history.

## Superseded reference material

The [`reference/`](reference/) directory contains former policy and flow documentation. It is retained for archaeology and may use terminology or guarantees replaced by the canonical design.

The [`performance/evidence/2026-08-27/`](performance/evidence/2026-08-27/) directory preserves an earlier qualification pair. It was superseded by the consecutive 29 August runs promoted in the [canonical performance evidence](../performance.md) and must not be used as the project's final capacity claim.

## Raw working material

- [`discovery/`](discovery/) contains the initial scope and research notes.
- [`internal/superpowers/`](internal/superpowers/) contains implementation plans and design drafts produced during development.

These files intentionally remain unpolished. They show how work was organized at the time, not how a reader should understand or operate the final system.
