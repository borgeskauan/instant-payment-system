# Final performance evidence

## Supported claim

The runtime at commit 1351ea564d0834a66e1b5d99a5e09a1a384cae1b sustained at least 2,000 original payments per second throughout a 15-minute active window, with end-to-end p99 below the internal one-second threshold and no functional violations, in two consecutive runs.

Both runs used the mixed-outcomes-2k-15m profile, the same normalized execution plan, a clean worktree, and newly prepared environments. Code, configuration, and procedure did not change between them.

## Qualification campaign

| Run | Local start | Planned / executed originals | Minimum rolling TPS | p99 | Correctness |
| --- | --- | ---: | ---: | ---: | --- |
| A | 2026-08-29 19:29:40 -03:00 | 1,890,000 / 1,889,369 | 2,017 | 855.202 ms | zero missing or contradictory outcomes and zero replay violations |
| B | 2026-08-29 19:49:50 -03:00 | 1,890,000 / 1,890,000 | 2,079 | 265.195 ms | zero missing or contradictory outcomes and zero replay violations |

Executed totals do not compensate for temporal deficits. Qualification requires every continuous one-second window fully contained in the active phase to reach 2,000 TPS. Both runs satisfied that criterion and the latency threshold independently.

Run A is deliberately preserved despite its higher tail latency. Selecting only the most favorable sample would weaken the campaign. Run A shows that throughput, latency, and correctness held under the least favorable observed condition; Run B establishes repeatability. The campaign therefore supports a p99 range of 265.195–855.202 ms rather than presenting 265.195 ms as a typical value. The smallest observed rolling headroom was 17 TPS.

## Qualifying artifacts

- [profile.json](profile.json): profile shared by both runs;
- [execution-plan.json](execution-plan.json): normalized plan shared by both runs;
- [qualification-run-a-sla-report.json](qualification-run-a-sla-report.json): Run A report;
- [qualification-run-b-sla-report.json](qualification-run-b-sla-report.json): Run B report;
- [checksums.sha256](checksums.sha256): checksums for the compact evidence in this directory.

Large CSVs, JFR recordings, logs, certificates, and credentials are not part of the canonical evidence. The versioned inputs and reports are sufficient to verify the promoted claim without access to local load-test/results directories.

## Separate Go/Rust study

[go-comparison-sla-report.json](go-comparison-sla-report.json) and [rust-comparison-sla-report.json](rust-comparison-sla-report.json) belong to the controlled generator comparison, not to the final capacity qualification above.

This separation is deliberate: the generator study asks which implementation preserves the workload more predictably; the two-run campaign asks whether the final runtime repeatedly satisfies the capacity contract. The interpretation of both is consolidated in [performance.md](../../../performance.md).
