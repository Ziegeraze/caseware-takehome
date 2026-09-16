# Caseware — Staff Java Developer take-home

| Deliverable | Location |
|---|---|
| 1. Design document | [`DESIGN.md`](DESIGN.md) · [PDF](Caseware-Design-Document.pdf) |
| 2. Part 2 implementation + README | [`part2-fanout-worker/`](part2-fanout-worker/) · [README](part2-fanout-worker/README.md) |
| 4. Diagrams | ASCII, inline: architecture and data residency + version DAG in `DESIGN.md`, worker flow in the Part 2 README |

```bash
cd part2-fanout-worker && mvn test    # 10 tests, JUnit 5 only; requires JDK 17+
```

## Reading order

1. `DESIGN.md` §0 — the arithmetic that forces the whole design (1 min/engagement → 57,000 h/month if
   done naively).
2. `DESIGN.md` §1–§2 — projection, data residency, and what "declined" actually pins in a version DAG.
3. `part2-fanout-worker/README.md` — implementation tradeoffs.

## Note on AI usage

Produced with Claude Code, in dialogue rather than by prompt-and-paste. The problem was worked in stages
— arithmetic, then data residency, then decline semantics, then event-delivery failure modes, then LLM
evaluation — with each conclusion argued before it was accepted, and the prose drafted from those
conclusions and reviewed afterwards.

Two observations that are worth more than the usage itself, and that I am happy to go into during the
review. First, AI-drafted arithmetic has to be verified: one multiplication in §4 was wrong until a
manual check caught it — the same failure class that §5 of the design deliberately engineers against
(code owns facts; the model owns prose). Second, the most valuable output was not the prose but the
adversarial questions: the design changed shape twice under them, once on which data may cross a region
boundary and once on what "declined" actually pins.

Full session transcript available on request.
