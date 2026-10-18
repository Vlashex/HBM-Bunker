# Prompt comparison evidence

See the [Russian qualitative report](../../docs/DEAD-EARTH-PROMPT-AB.md).

- `PROTOCOL.md`, `suite.json`: GPU design and 12 new cases, frozen in d849f35
  before inference. Three prompts, two checkpoints, 72 full generated answers.
- `CPU-PROTOCOL.md`, `cpu-protocol.json`: CPU selector A/C and critic D/E,
  frozen in 5e603fc before requests. Four fixed cases, 16 full answers.
- `gpu/`, `cpu/`: immutable copies of `run.json`, `responses.jsonl`, `key.json`,
  and `blind.json` from completed runs. No failed responses were removed/retried.
- `manual-gpu-blind-review.json`: all 72 opaque IDs assessed directly by Codex
  root, committed in 156cc20 before reading GPU response identities.
- `manual-cpu-blind-review.json`: all 16 opaque IDs assessed directly by Codex
  root, committed in c60b0db before reading CPU response identities.
- `provenance.json`: old split counts/hashes and exact-context non-overlap.
  Original byte hashes can reflect Windows CRLF (suite, CPU output, reviews);
  Git normalizes text to LF. The manifest records original and normalized hashes;
  `suite_lf_sha256` also covers the Git LF suite text.
- `cpu-unload.json`: final `/api/ps` check after keep_alive=0.
- `manifest.json`: normalized LF hashes of evidence, serving/training image
  identities, verification and cleanup receipt. This is an integrity inventory,
  not a semantic evaluation script or score.

The grouped hand-written comment applies separately to each explicitly listed
response ID. There is no reference target index, automatic policy grade, judge
model, or aggregate quality score. JSON/index validation is strictly technical.
Original run files say `manual_blind_review_pending`: that was true when the
generator finished; the separate committed manual reviews complete that step.

All contexts are authored public-safe fixtures, not live world/player/chat data.
Only compact generated event indices/reason codes are archived. Model weights,
training cache, service logs, secrets, and private runtime files are excluded.

The base/adapter run uses the pinned existing training image and no network.
Runner: `python -m training.prompt_ab --help` from `director/`; CPU runner:
`python -m training.prompt_ab_cpu --help`. Output directories must be new.
See the protocols for mounts and bounds. Production prompts/weights are unchanged.
