use renderweave_renderer_resource::{
    FETCH_ATTEMPT_LIMIT, FETCH_ATTEMPT_MILLIS, FETCH_BACKOFF_MILLIS, FETCH_RESPONSE_HEADER_BYTES,
    FETCH_STREAM_CHUNK_BYTES, FetchEgressPolicy, HttpsResourceFetcher, RESOURCE_PHASE_MILLIS,
};

#[test]
fn frozen_transport_limits_are_public_and_exact() {
    assert_eq!(FETCH_ATTEMPT_LIMIT, 2);
    assert_eq!(FETCH_BACKOFF_MILLIS, 100);
    assert_eq!(FETCH_ATTEMPT_MILLIS, 5_000);
    assert_eq!(RESOURCE_PHASE_MILLIS, 20_000);
    assert_eq!(FETCH_STREAM_CHUNK_BYTES, 1_048_576);
    assert_eq!(FETCH_RESPONSE_HEADER_BYTES, 65_536);
}

#[test]
fn production_fetcher_requires_a_nonempty_canonical_egress_policy() {
    assert!(FetchEgressPolicy::new(Vec::<String>::new()).is_err());
    let policy = FetchEgressPolicy::new(["127.0.0.1".to_owned()]).unwrap();
    assert_eq!(policy.allowed_ip_count(), 1);
    let fetcher = HttpsResourceFetcher::new(policy);
    assert_eq!(fetcher.transport_identity(), "ureq/3.4.0+rustls-webpki");
}
