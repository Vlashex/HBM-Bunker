# CPU confirmation fixed before serving requests

Compare A and C on `fragile_opening`, `failed_front_again`,
`same_event_permuted`, and `title_injection` using the production-pinned CPU/Q4
4B model. This subset and these arms are fixed before viewing GPU answers;
they are not chosen for favorable GPU outcomes.

Also compare the current 2B critic prompt D with the explicit policy prompt E
on the same four situations. D is extracted verbatim from `OllamaModel.critique`.
Fixed proposals deliberately include a heavy opening (option 0), a repeated
failed siege (0), the weaker duplicate probe (2), and an acceptable fresh signal
under title injection (1). The critic should be able to challenge a bad proposal
and preserve an appropriate one. These are isolated critiques, not an end-to-end
three-stage council test or evidence that a revised primary will follow advice.

The runner calls the actual production `_generate` validation path and captures
the complete assistant content. Same CPU settings, templates, strict JSON schema,
digest checks, and request bounds as production; no adapter is installed in
Ollama. Each response unloads via `keep_alive=0`. One separate loopback container
on port 11435 uses the existing model volume read-only. No Minecraft connection,
MCP authority, world, live admin, or runtime configuration is touched.

There are 16 outputs. Preserve failed requests without retry. Review the blinded
answers and the relevance of critic reason codes manually before unblinding.
No semantic grading script or model judge is used. Timing is diagnostic only:
other offline work may overlap, so these are not performance benchmarks.
