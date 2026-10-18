# Frozen prompt comparison — 2026-10-18

Purpose: manually assess the actual event selections of the base Qwen3.5-4B and
the existing 48-update LoRA candidate. This experiment does not train, promote,
or modify production models, prompts, saves, or configuration.

Before inference, freeze `suite.json` and the runner in Git. The suite contains
12 newly authored situations and three system prompts: A is the existing
production selector, B is a shorter policy, C is an explicit decision checklist.
Case-specific review notes are written before generation and are never sent to
the model. The contexts have no exact overlap with the previous 692 training,
validation, and test contexts. This is an authored stress test, not live gameplay.

Run every case with every prompt and both checkpoints: 72 complete generated
answers, deterministic greedy decoding, no forced answer prefix, no reference
option labels. Both checkpoints share the pinned base and NF4/FP16 backend;
the adapter is enabled or disabled for each shuffled job. The offline GPU run
uses the existing training image, no network, a read-only model cache, and
isolated output directories. Production inference remains CPU-only.

The automatic checker only validates the JSON/index contract. It assigns no
semantic score. Review `blind.json` before reading `key.json` or the identified
response log. Write and save a manual verdict and explanation for every opaque
response ID, using only facts visible in its context. Permit multiple reasonable
choices and explicitly record insufficient evidence. No secondary model judges
the output. Unblind only after saving the review, then compare concrete decisions
and tradeoffs rather than selecting a winner by synthetic accuracy.

If feasible, confirm the most promising prompts on a small paired subset using
the actual CPU/Q4 serving backend. Report this separately: quantization, serving
templates, and runtime differ from the adapter experiment. Do not claim gameplay
effectiveness, generalization, improved tactical intelligence, or successful
training from this small qualitative comparison.

Keep all raw outputs and immutable run metadata. Public artifacts contain only
the authored contexts and their generated event indices, no private world/chat
data, credentials, model weights, or local serving state. Record failures rather
than silently restarting or discarding inconvenient outputs.
