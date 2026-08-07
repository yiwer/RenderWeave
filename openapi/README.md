# OpenAPI contract

`renderweave-v1.yaml` is the HTTP source of truth. Product operations are added only with the vertical slice that implements and tests them; this prevents speculative endpoints from becoming accidental commitments.

- Dialect: OpenAPI 3.1.2.
- Path base: `/api/v1`.
- Java controllers/DTOs are handwritten and contract-tested.
- TypeScript types and Fetch SDK are generated with the exact-pinned Hey API CLI.
- SSE clients remain handwritten because events are notifications and GET snapshots are authoritative.
