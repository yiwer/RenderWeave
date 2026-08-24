use renderweave_renderer_document::validate_render_document;
use renderweave_renderer_layout::{
    LocalLayoutBox, layout_definite_resource_free, layout_definite_with_prepared_resources,
};
use renderweave_renderer_resource::{
    ASSET_FETCH_PATH_PREFIX, AdmittedFetchTarget, FetchTargetPolicy, FetchedResource,
    ManifestResourcePreparer, RequestResourceFetchState, ResourceFetchProblem, ResourceFetcher,
    ResourcePreparationProfile,
};
use serde_json::Value;

const VECTORS: &str = include_str!("../../../prepared-image-layout-vectors-v1.json");
const DEADLINE: i64 = 2_000_000_000_000;
const STARTED: i64 = 1_900_000_000_000;

struct FixtureFetcher {
    bytes: Box<[u8]>,
}

impl ResourceFetcher for FixtureFetcher {
    fn fetch_resource(
        &self,
        target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        state: &mut RequestResourceFetchState,
    ) -> Result<FetchedResource, ResourceFetchProblem> {
        state.verify_owned_body(target, self.bytes.clone())
    }
}

#[test]
fn replays_prepared_oriented_image_intrinsic_vectors_through_the_public_interface() {
    let vectors: Value = serde_json::from_str(VECTORS).unwrap();
    assert_eq!(
        vectors["vectorVersion"],
        "renderweave-prepared-image-layout-vectors/1"
    );
    let bytes = hex::decode(vectors["resourceFixture"]["bodyHex"].as_str().unwrap())
        .unwrap()
        .into_boxed_slice();
    let policy =
        FetchTargetPolicy::new("https://render.internal.example", ASSET_FETCH_PATH_PREFIX).unwrap();

    for case in vectors["successCases"].as_array().unwrap() {
        let document = case_document(&vectors["documentTemplate"], case);
        let admitted = validate_render_document(&serde_json::to_string(&document).unwrap())
            .unwrap_or_else(|error| panic!("{} admission failed: {error}", case["id"]));
        let fetcher = FixtureFetcher {
            bytes: bytes.clone(),
        };
        let manifest = ManifestResourcePreparer::new(
            &policy,
            &fetcher,
            ResourcePreparationProfile::RendererV1,
        )
        .prepare(admitted.resources(), DEADLINE, STARTED)
        .unwrap_or_else(|error| panic!("{} preparation failed: {error:?}", case["id"]));

        let layout = layout_definite_with_prepared_resources(&admitted, &manifest)
            .unwrap_or_else(|error| panic!("{} layout failed: {error}", case["id"]));
        assert_eq!(
            layout.entries().len(),
            case["expectedEntryCount"].as_u64().unwrap() as usize,
            "{}",
            case["id"]
        );
        for expected in case["expectedEntries"].as_array().unwrap() {
            let occurrence_id = expected["occurrenceId"].as_str().unwrap();
            let actual = layout
                .entries()
                .iter()
                .find(|entry| entry.occurrence_id() == occurrence_id)
                .unwrap_or_else(|| panic!("{} missing {occurrence_id}", case["id"]));
            assert_eq!(actual.kind(), expected["kind"], "{}", case["id"]);
            assert_box_bits(actual.layout_box(), &expected["layoutBox"], &case["id"]);
            match (actual.content_box(), &expected["contentBox"]) {
                (Some(actual), expected) if expected.is_object() => {
                    assert_box_bits(actual, expected, &case["id"]);
                }
                (None, expected) if expected.is_null() => {}
                _ => panic!("{} ContentBox presence drifted", case["id"]),
            }
        }
    }

    let boundary = &vectors["authorityContext"];
    assert_eq!(boundary["profileAvailability"], "NOT_REGISTERED");
    assert_eq!(boundary["certificationStatus"], "NOT_CERTIFIED");
    assert_eq!(boundary["sceneImplementation"], "ABSENT");
    assert_eq!(boundary["rasterImplementation"], "ABSENT");
    assert_eq!(boundary["daemonOutputPath"], "UNWIRED");
    assert_eq!(boundary["productRoute"], "CLOSED");
    assert_eq!(boundary["providerAttempts"], 0);
}

#[test]
fn prepared_manifest_identity_is_atomic_and_resource_free_boundary_is_unchanged() {
    let vectors: Value = serde_json::from_str(VECTORS).unwrap();
    let bytes = hex::decode(vectors["resourceFixture"]["bodyHex"].as_str().unwrap())
        .unwrap()
        .into_boxed_slice();
    let canonical = serde_json::to_string(&vectors["documentTemplate"]).unwrap();
    let admitted = validate_render_document(&canonical).unwrap();
    let policy =
        FetchTargetPolicy::new("https://render.internal.example", ASSET_FETCH_PATH_PREFIX).unwrap();
    let fetcher = FixtureFetcher { bytes };
    let manifest =
        ManifestResourcePreparer::new(&policy, &fetcher, ResourcePreparationProfile::RendererV1)
            .prepare(admitted.resources(), DEADLINE, STARTED)
            .unwrap();

    let resource_free = layout_definite_resource_free(&admitted).unwrap_err();
    assert_eq!(
        resource_free.occurrence_id(),
        vectors["resourceFreeBoundary"]["expectedOccurrenceId"]
    );
    assert_eq!(
        resource_free.unsupported_feature().unwrap().as_str(),
        vectors["resourceFreeBoundary"]["expectedFeature"]
    );

    let mismatch = &vectors["manifestMismatchCase"];
    let replacement = mismatch["replacementResourceId"].clone();
    let mut mismatched_document = vectors["documentTemplate"].clone();
    mismatched_document["canvas"]["children"][0]["imageResourceId"] = replacement.clone();
    mismatched_document["resources"][0]["resourceId"] = replacement;
    let mismatched =
        validate_render_document(&serde_json::to_string(&mismatched_document).unwrap()).unwrap();
    let error = layout_definite_with_prepared_resources(&mismatched, &manifest).unwrap_err();
    assert_eq!(error.occurrence_id(), mismatch["expectedOccurrenceId"]);
    assert_eq!(
        error.invariant_property().unwrap(),
        mismatch["expectedInvariant"]
    );
}

fn case_document(template: &Value, case: &Value) -> Value {
    let mut document = template.clone();
    for mutation in case["mutations"].as_array().unwrap() {
        let pointer = mutation["pointer"].as_str().unwrap();
        let (parent_pointer, token) = pointer.rsplit_once('/').unwrap();
        let token = token.replace("~1", "/").replace("~0", "~");
        let parent = document.pointer_mut(parent_pointer).unwrap();
        match (parent, mutation["operation"].as_str().unwrap()) {
            (Value::Object(object), "remove") => {
                object.remove(&token).unwrap();
            }
            (Value::Object(object), "add" | "replace") => {
                object.insert(token, mutation["value"].clone());
            }
            (Value::Array(array), "remove") => {
                array.remove(token.parse().unwrap());
            }
            (Value::Array(array), "add") => {
                array.insert(token.parse().unwrap(), mutation["value"].clone());
            }
            (Value::Array(array), "replace") => {
                array[token.parse::<usize>().unwrap()] = mutation["value"].clone();
            }
            (_, operation) => panic!("unknown mutation operation {operation}"),
        }
    }
    document
}

fn assert_box_bits(actual: &LocalLayoutBox, expected: &Value, case_id: &Value) {
    for (member, value) in [
        ("x", actual.x()),
        ("y", actual.y()),
        ("width", actual.width()),
        ("height", actual.height()),
    ] {
        assert_eq!(
            value.to_bits(),
            expected[member].as_f64().unwrap().to_bits(),
            "{case_id}: {member}"
        );
    }
}
