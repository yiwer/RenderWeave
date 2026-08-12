# Layered visual corpus source notice

The scene descriptions in `visual-eval/v1/scenes.json` were authored for RenderWeave as
deterministic synthetic evaluation fixtures. They contain no customer artifact, user image,
production payload, historical live-provider payload, or real business record.

Corpus 2.0 derives versioned layered annotations from those immutable scenes and renders them
locally with the separately inventoried OFL font subset. No network fetch or external model call
is part of corpus construction.
