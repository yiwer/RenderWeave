use renderweave_renderer_resource::{
    AdmittedFetchTarget, FetchTargetPolicy, FetchedResource, ManifestResourcePreparer,
    PreparedRenderResource, RequestResourceFetchState, ResourceFetchProblem, ResourceFetcher,
    ResourcePreparationProfile,
};

struct NeverFetch;

impl ResourceFetcher for NeverFetch {
    fn fetch_resource(
        &self,
        _target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        _state: &mut RequestResourceFetchState,
        _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
    ) -> Result<FetchedResource, ResourceFetchProblem> {
        panic!("an empty manifest must not fetch")
    }
}

#[test]
fn public_pipeline_seals_an_empty_manifest_without_transport() {
    let policy = FetchTargetPolicy::new(
        "https://render.internal.example",
        renderweave_renderer_resource::ASSET_FETCH_PATH_PREFIX,
    )
    .unwrap();
    let fetcher = NeverFetch;
    let preparer =
        ManifestResourcePreparer::new(&policy, &fetcher, ResourcePreparationProfile::RendererV1);

    let manifest = preparer
        .prepare(&[], 2_000_000_000_000, 1_900_000_000_000)
        .unwrap();
    let resources: &[PreparedRenderResource] = manifest.resources();
    assert!(resources.is_empty());
    assert_eq!(manifest.profile().as_str(), "renderweave-renderer/1.0");
}
