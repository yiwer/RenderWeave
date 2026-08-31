#![cfg(feature = "native-text-skia")]

use std::collections::BTreeMap;

use renderweave_renderer_document::{AdmittedRenderDocument, validate_render_document};
use renderweave_renderer_engine::{EnginePngUnsupported, render_png_with_prepared_resources};
use renderweave_renderer_resource::{
    ASSET_FETCH_PATH_PREFIX, AdmittedFetchTarget, FetchTargetPolicy, ManifestResourcePreparer,
    PreparedResourceManifest, RequestResourceFetchState, ResourceFetchProblem, ResourceFetcher,
    ResourcePipelineProblem, ResourcePreparationProfile,
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
const FONT_BYTES: &[u8] = include_bytes!(
    "../../../../renderweave-asset/src/test/resources/asset-fixtures/minimal-ttf.ttf"
);
const CFF_FONT_BYTES: &[u8] = include_bytes!(
    "../../../../renderweave-asset/src/test/resources/asset-fixtures/minimal-otf.otf"
);
const DEFAULT_SUBSTITUTION_FONT_HEX: &str =
    include_str!("../../../fixtures/default-substitution-font-v1.hex");
const TRICKY_FONT_HEX: &str = include_str!("../../../fixtures/tricky-font-v1.hex");
const FETCH_ORIGIN: &str = "https://render.internal.example";
const RESOURCE_ID: &str = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const DEADLINE_EPOCH_MILLIS: i64 = 2_000_000_000_000;
const STARTED_EPOCH_MILLIS: i64 = 1_900_000_000_000;
const GOLDEN_BYTE_LENGTH: usize = 18_582;
const GOLDEN_PIXEL_SHA256: &str =
    "sha256:afdff21b99f5e7101c692c16744602e8a621fb3a814555cd1493b62ae2c5b3a4";
const GOLDEN_CONTENT_SHA256: &str =
    "sha256:77c3a0195d424998a55595a52b305344c86efe5f770884f7d1cd639c63be936b";

#[test]
fn renders_one_exact_font_text_node_to_a_deterministic_complete_png() {
    let document = admitted_text_document();
    let manifest = prepared_font_manifest(&document);

    let first = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect("the production Text slice must render");
    let second = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect("the same production Text slice must replay");

    assert_eq!(96, first.width_px());
    assert_eq!(48, first.height_px());
    assert_eq!(first.pixel_sha256(), second.pixel_sha256());
    assert_eq!(first.content_sha256(), second.content_sha256());
    assert_eq!(first.bytes(), second.bytes());
    assert_eq!(&first.bytes()[..8], b"\x89PNG\r\n\x1a\n");
    assert_eq!(GOLDEN_BYTE_LENGTH, first.byte_length());
    assert_eq!(GOLDEN_PIXEL_SHA256, first.pixel_sha256());
    assert_eq!(GOLDEN_CONTENT_SHA256, first.content_sha256());

    let blank = vec![255_u8; 96 * 48 * 4];
    let blank_sha256 = format!("sha256:{}", hex::encode(Sha256::digest(blank)));
    assert_ne!(blank_sha256, first.pixel_sha256());
}

#[test]
fn exact_font_missing_glyph_fails_without_an_output() {
    let document = admitted_text_document_with(|document| {
        document["canvas"]["children"][0]["runs"][0]["text"] = Value::String("B".to_owned());
    });
    let manifest = prepared_font_manifest(&document);

    let problem = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect_err("the exact font must never fall back for a missing glyph");

    assert_eq!("FONT_GLYPH_MISSING", problem.code());
    assert_eq!("SHAPING", problem.stage());
    assert_eq!(Some("rwocc_0000000000000001"), problem.occurrence_id());
    assert_eq!(Some(RESOURCE_ID), problem.resource_id());
}

#[test]
fn unsupported_text_contract_fails_before_output_seal() {
    let document = admitted_text_document_with(|document| {
        document["canvas"]["children"][0]["horizontalAlign"] = Value::String("CENTER".to_owned());
    });
    let manifest = prepared_font_manifest(&document);

    let problem = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect_err("the production Text slice must reject an unsupported contract");

    assert_eq!(
        Some(EnginePngUnsupported::TextPaint.as_str()),
        problem.unsupported_feature()
    );
}

#[test]
fn multi_glyph_shaping_is_not_claimed_by_the_single_glyph_slice() {
    let document = admitted_text_document_with(|document| {
        document["canvas"]["children"][0]["runs"][0]["text"] = Value::String("AA".to_owned());
    });
    let manifest = prepared_font_manifest(&document);

    let problem = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect_err("multi-glyph shaping belongs to a later production slice");

    assert_eq!(
        Some(EnginePngUnsupported::TextPaint.as_str()),
        problem.unsupported_feature()
    );
}

#[test]
fn empty_text_is_not_a_background_only_escape_from_the_closed_slice() {
    let document = admitted_text_document_with(|document| {
        document["canvas"]["children"][0]["runs"][0]["text"] = Value::String(String::new());
    });
    let manifest = prepared_font_manifest(&document);

    let problem = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect_err("the production Text slice admits exactly one Latin letter");

    assert_eq!(
        Some(EnginePngUnsupported::TextPaint.as_str()),
        problem.unsupported_feature()
    );
}

#[test]
fn ascii_outside_the_frozen_latin_script_contract_is_rejected_before_shaping() {
    for scalar in ["1", "!"] {
        let document = admitted_text_document_with(|document| {
            document["canvas"]["children"][0]["runs"][0]["text"] = Value::String(scalar.to_owned());
        });
        let manifest = prepared_font_manifest(&document);

        let problem = render_png_with_prepared_resources(&document, &manifest, 96)
            .expect_err("script-incompatible ASCII must not enter LATIN shaping");

        assert_eq!(
            Some(EnginePngUnsupported::TextPaint.as_str()),
            problem.unsupported_feature(),
            "{scalar} must remain outside the exact one-Latin-letter slice"
        );
    }
}

#[test]
fn frozen_shaper_applies_default_gsub_for_the_single_scalar_slice() {
    let font_bytes = hex::decode(DEFAULT_SUBSTITUTION_FONT_HEX.trim())
        .expect("frozen GSUB fixture must be lowercase hex");
    let document = admitted_text_document_with_font(&font_bytes, |_| {});
    let manifest = prepare_font_manifest(
        &document,
        &font_bytes,
        DEADLINE_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect("GSUB proof font must prepare");

    let output = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect("single-scalar default shaping must render");

    assert_eq!(
        "sha256:8fae18975da6386236ffb7733472b3d7cec1fdd7dad601bd1bbe1daf4646ff58",
        output.pixel_sha256(),
        "the frozen cmap A -> A plus default ccmp A -> A.alt fixture must rasterize A.alt"
    );
}

#[test]
fn cff_outline_path_is_byte_deterministic_under_the_production_policy() {
    assert_eq!(
        "sha256:eeef766ac75aecac694bbd82fbb3cd2b9a315075db14d91ff0cbe1bdec20f77f",
        format!("sha256:{}", hex::encode(Sha256::digest(CFF_FONT_BYTES)))
    );
    let document = admitted_text_document_with_font(CFF_FONT_BYTES, |document| {
        document["resources"][0]["mediaType"] = Value::String("font/otf".to_owned());
        document["resources"][0]["technicalDescriptor"]["flavor"] = Value::String("CFF".to_owned());
    });
    let manifest = prepare_font_manifest(
        &document,
        CFF_FONT_BYTES,
        DEADLINE_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect("the frozen CFF fixture must prepare");

    let first = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect("CFF outlines must remain functional without a PostScript hinter");
    let second = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect("the same CFF command must replay");

    assert_eq!(first.bytes(), second.bytes());
    assert_eq!(
        "sha256:630063ab4f18a8c0dd5341ec961b9fd95a4e1dabde296499c64cfdf0571a2215",
        first.pixel_sha256()
    );
    assert_eq!(
        "sha256:555f5dca44d2be490a21f1731367d0b5b3d07ad7aa0485a45c58f49ebf252895",
        first.content_sha256()
    );
}

#[test]
fn tricky_true_type_path_is_deterministic_without_bytecode_execution() {
    let font_bytes = hex::decode(TRICKY_FONT_HEX.trim())
        .expect("the frozen tricky-font fixture must be lowercase hex");
    assert_eq!(
        "sha256:315504d5386a2e53f0c96cd3efbf71b9ccc3b1fef237dbec9e7d25cdbcf7139f",
        format!("sha256:{}", hex::encode(Sha256::digest(&font_bytes)))
    );
    let document = admitted_text_document_with_font(&font_bytes, |_| {});
    let manifest = prepare_font_manifest(
        &document,
        &font_bytes,
        DEADLINE_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect("the frozen tricky TrueType fixture must prepare");

    let first = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect("required flags must prevent bytecode while preserving outlines");
    let second = render_png_with_prepared_resources(&document, &manifest, 96)
        .expect("the same tricky-font command must replay");

    assert_eq!(first.bytes(), second.bytes());
    assert_eq!(
        "sha256:c698e4d98a1d073bce25dbc5d1638c74bf3beaab9e490ec8039cd8a093608cab",
        first.pixel_sha256()
    );
    assert_eq!(
        "sha256:cd6c38b7dcef6b5998d166e3480a133e21fa070b7ccf0da94114ba569049f6ee",
        first.content_sha256()
    );
}

#[test]
fn font_hash_mismatch_fails_during_resource_preparation() {
    let document = admitted_text_document();
    let mut tampered = FONT_BYTES.to_vec();
    tampered[0] ^= 0xff;

    let problem = prepare_font_manifest(
        &document,
        &tampered,
        DEADLINE_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect_err("tampered exact FONT bytes must fail before shaping");

    assert_eq!("HASH_MISMATCH", problem.code());
    assert_eq!("RESOURCE_PREPARATION", problem.engine_stage());
    assert_eq!(Some(RESOURCE_ID), problem.resource_id());
}

#[test]
fn font_media_mismatch_fails_during_resource_preparation() {
    let document = admitted_text_document_with(|document| {
        document["resources"][0]["mediaType"] = Value::String("font/otf".to_owned());
        document["resources"][0]["technicalDescriptor"]["flavor"] = Value::String("CFF".to_owned());
    });

    let problem = prepare_font_manifest(
        &document,
        FONT_BYTES,
        DEADLINE_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect_err("declared OTF must not accept exact TTF bytes");

    assert_eq!("MEDIA_MISMATCH", problem.code());
    assert_eq!("RESOURCE_PREPARATION", problem.engine_stage());
    assert_eq!(Some(RESOURCE_ID), problem.resource_id());
}

#[test]
fn font_descriptor_mismatch_fails_during_resource_preparation() {
    let document = admitted_text_document_with(|document| {
        document["resources"][0]["technicalDescriptor"]["unitsPerEm"] = json!(999);
    });

    let problem = prepare_font_manifest(
        &document,
        FONT_BYTES,
        DEADLINE_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect_err("declared font metrics must exactly match the admitted bytes");

    assert_eq!("RENDER_INTERNAL_ERROR", problem.code());
    assert_eq!("RESOURCE_PREPARATION", problem.engine_stage());
    assert_eq!(None, problem.resource_id());
}

#[test]
fn elapsed_deadline_fails_before_resource_fetch_or_output() {
    let document = admitted_text_document();

    let problem = prepare_font_manifest(
        &document,
        FONT_BYTES,
        STARTED_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect_err("an elapsed deadline must fail before resource work");

    assert_eq!("RENDER_DEADLINE_EXCEEDED", problem.code());
    assert_eq!("RESOURCE_PREPARATION", problem.engine_stage());
    assert_eq!(None, problem.resource_id());
}

fn admitted_text_document() -> AdmittedRenderDocument {
    admitted_text_document_with(|_| {})
}

fn admitted_text_document_with(mutate: impl FnOnce(&mut Value)) -> AdmittedRenderDocument {
    admitted_text_document_with_font(FONT_BYTES, mutate)
}

fn admitted_text_document_with_font(
    font_bytes: &[u8],
    mutate: impl FnOnce(&mut Value),
) -> AdmittedRenderDocument {
    let mut document: Value = serde_json::from_str(ALL_KINDS).expect("all-kinds document");
    let mut text = document["canvas"]["children"][4].clone();
    text["occurrenceId"] = Value::String("rwocc_0000000000000001".to_owned());
    text["placement"]["xPt"] = json!(6);
    text["placement"]["yPt"] = json!(6);
    text["placement"]["widthPt"] = json!(60);
    text["placement"]["heightPt"] = json!(24);
    text["runs"][0]["fontResourceId"] = Value::String(RESOURCE_ID.to_owned());
    text["runs"][0]["fontSizePt"] = json!(12);
    text["runs"][0]["text"] = Value::String("A".to_owned());

    document["canvas"]["widthPt"] = json!(72);
    document["canvas"]["heightPt"] = json!(36);
    document["canvas"]["backgroundColor"] = Value::String("#FFFFFFFF".to_owned());
    document["canvas"]["children"] = Value::Array(vec![text]);
    document["resources"] = Value::Array(vec![font_resource(font_bytes)]);
    mutate(&mut document);

    validate_render_document(&serde_json::to_string(&document).expect("Text document JSON"))
        .expect("Text document must pass public admission")
}

fn font_resource(font_bytes: &[u8]) -> Value {
    json!({
        "resourceId": RESOURCE_ID,
        "kind": "font",
        "fetchUrl": format!("{FETCH_ORIGIN}{ASSET_FETCH_PATH_PREFIX}/font"),
        "expiresAt": DEADLINE_EPOCH_MILLIS,
        "sha256": format!("sha256:{}", hex::encode(Sha256::digest(font_bytes))),
        "mediaType": "font/ttf",
        "byteLength": font_bytes.len(),
        "acceptanceProfileId": "renderweave-asset-acceptance/1.0",
        "technicalDescriptor": {
            "kind": "font",
            "faceIndex": 0,
            "flavor": "TRUETYPE_GLYF",
            "unitsPerEm": 1000
        }
    })
}

fn prepared_font_manifest(document: &AdmittedRenderDocument) -> PreparedResourceManifest {
    prepare_font_manifest(
        document,
        FONT_BYTES,
        DEADLINE_EPOCH_MILLIS,
        STARTED_EPOCH_MILLIS,
    )
    .expect("exact FONT must prepare before shaping")
}

fn prepare_font_manifest(
    document: &AdmittedRenderDocument,
    body: &[u8],
    deadline_epoch_millis: i64,
    started_epoch_millis: i64,
) -> Result<PreparedResourceManifest, ResourcePipelineProblem> {
    let policy =
        FetchTargetPolicy::new(FETCH_ORIGIN, ASSET_FETCH_PATH_PREFIX).expect("fixed fetch policy");
    let fetcher = FontFetcher {
        bodies: BTreeMap::from([(RESOURCE_ID.to_owned(), body.to_vec())]),
    };
    ManifestResourcePreparer::new(&policy, &fetcher, ResourcePreparationProfile::RendererV1)
        .prepare(
            document.resources(),
            deadline_epoch_millis,
            started_epoch_millis,
        )
}

struct FontFetcher {
    bodies: BTreeMap<String, Vec<u8>>,
}

impl ResourceFetcher for FontFetcher {
    fn fetch_resource(
        &self,
        target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        state: &mut RequestResourceFetchState,
        _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
    ) -> Result<renderweave_renderer_resource::FetchedResource, ResourceFetchProblem> {
        let body = self
            .bodies
            .get(target.resource_id())
            .ok_or_else(|| ResourceFetchProblem::fetch_failed(target.resource_id()))?;
        state.verify_owned_body(target, body.clone().into_boxed_slice())
    }
}
