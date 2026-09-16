# Caseware — Staff Java Developer take-home

| Deliverable | Location |
|---|---|
| 1. Design document | [`DESIGN.md`](DESIGN.md) |
| 2. Part 2 implementation + README | [`part2-fanout-worker/`](part2-fanout-worker/) · [README](part2-fanout-worker/README.md) |
| 4. Diagrams | ASCII, inline in `DESIGN.md` (architecture / residency, version DAG, worker flow) |

```bash
cd part2-fanout-worker && mvn test    # 10 tests, Java 17, JUnit 5 only
```

## Reading order

1. `DESIGN.md` §0 — the arithmetic that forces the whole design (1 min/engagement → 57,000 h/month if
   done naively).
2. `DESIGN.md` §1–§2 — projection, data residency, and what "declined" actually pins in a version DAG.
3. `part2-fanout-worker/README.md` — implementation tradeoffs.

## Note on AI usage

This submission was produced with Claude as a working partner, used as a reviewer rather than an author:
the exercise was worked through as a sequence of questions (arithmetic, residency classification,
decline semantics, event-delivery failure modes, LLM evaluation), with each answer challenged before it
went into the document. Places where the AI's first framing was corrected, and the things it should not
be trusted with in this domain, are discussed in `DESIGN.md` §5 (code owns facts, the model owns prose)
and are available as the full session transcript on request.
