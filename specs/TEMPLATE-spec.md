# Feature spec: ____

> Prefer `to-spec` to synthesize this document from an already discussed feature. Use exact vocabulary from `CONTEXT.md` and respect relevant ADRs.

Status: proposed / approved / superseded

## Problem Statement

Describe the problem from the user's perspective.

## Solution

Describe the observable solution from the user's perspective.

## User Stories

1. As a __, I want __, so that __.

Cover the main path, empty state, invalid input, authorization, concurrency/idempotency, failure/recovery, compatibility and data lifecycle where relevant.

## Implementation Decisions

- Affected domain concepts and module ownership: __.
- Existing interface or highest behavior seam to reuse: __.
- API/schema/persistence/state decisions: __.
- Important failure and recovery semantics: __.

Record durable decisions, not guessed file paths or implementation snippets. Use an ADR when alternatives have materially different long-term consequences.

## Testing Decisions

- Highest observable seam: __.
- Expected external behavior: __.
- Existing test precedent to follow: __.
- PostgreSQL, cross-language, browser or property/golden coverage needed: __.

Test behavior and contracts rather than private implementation details.

## Out of Scope

- __.

## Further Notes

- Assumptions and unresolved decisions: __.
- Required explicit authorization, if any: __.
