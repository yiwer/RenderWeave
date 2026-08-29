use renderweave_renderer_daemon::{TerminalResponse, seal_prepared_png_result};
use renderweave_renderer_document::validate_render_document;
use renderweave_renderer_protocol::{FrameType, parse_command};
use renderweave_renderer_resource::{
    ASSET_FETCH_PATH_PREFIX, AdmittedFetchTarget, FetchTargetPolicy, FetchedResource,
    ManifestResourcePreparer, RequestResourceFetchState, ResourceFetchProblem, ResourceFetcher,
    ResourcePreparationProfile,
};
use serde_json::{Value, json};
use std::env;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};

const EXECUTION_CLASS: &str = "EXEC::RENDERING_PIPELINE::1.0";
const FETCH_ORIGIN: &str = "https://render.internal.example";
const STARTED_EPOCH_MILLIS: i64 = 1_800_000_000_000;

#[test]
fn replays_java_seal_through_public_parser_document_engine_and_result_chain() {
    let Some(paths) = evidence_paths() else {
        return;
    };
    let command_bytes =
        std::fs::read(&paths.command).expect("exact Java Renderer Command must be readable");
    let command = parse_command(&command_bytes).expect("Java command must pass public admission");
    let document = validate_render_document(command.command.document.get())
        .expect("Java RenderDocument must pass public document admission");
    assert!(
        document.resources().is_empty(),
        "execution target must be resource-free"
    );

    let fetch_policy = FetchTargetPolicy::new(FETCH_ORIGIN, ASSET_FETCH_PATH_PREFIX)
        .expect("fixed target fetch policy must be valid");
    let prepared = ManifestResourcePreparer::new(
        &fetch_policy,
        &NoFetch,
        ResourcePreparationProfile::RendererV1,
    )
    .prepare(
        document.resources(),
        command.deadline_epoch_millis,
        STARTED_EPOCH_MILLIS,
    )
    .expect("empty manifest preparation must succeed without fetch");
    let sealed = seal_prepared_png_result(&command, &document, &prepared)
        .expect("public Engine/result seal chain must produce one complete PNG");

    let metadata_bytes = sealed.metadata_payload().to_vec();
    let image_payload = sealed.image_payload().to_vec();
    assert!(
        image_payload.len() > 16,
        "terminal image frame must carry UUID and PNG"
    );
    let image_bytes = &image_payload[16..];
    assert_eq!(&image_bytes[..8], b"\x89PNG\r\n\x1a\n");
    assert_eq!(image_bytes.len() as u64, sealed.byte_length());
    let result_content_sha256 = sealed.content_sha256().to_owned();
    let artifact_sha256 = format!("sha256:{result_content_sha256}");
    let metadata: Value =
        serde_json::from_slice(&metadata_bytes).expect("terminal metadata must be strict JSON");
    assert_eq!(metadata["contentSha256"], result_content_sha256);
    assert_eq!(metadata["byteLength"], sealed.byte_length());
    assert_eq!(metadata["format"], "PNG");

    let terminal = TerminalResponse::sealed_result(sealed);
    assert_eq!(2, terminal.frames().len());
    assert_eq!(FrameType::ResultMetadata, terminal.frames()[0].frame_type);
    assert_eq!(FrameType::ResultImage, terminal.frames()[1].frame_type);
    write_new(&paths.image, image_bytes);

    let report = json!({
        "reportVersion": "renderweave-rendering-pipeline-rust-executor/1",
        "engine": "rust-render-document-parser-and-engine",
        "assurance": "A2_CROSS_LANGUAGE_PRODUCT_EXECUTION",
        "executionClass": EXECUTION_CLASS,
        "commandArtifact": {
            "path": file_name(&paths.command),
            "byteLength": command_bytes.len(),
            "commandDigest": command.command_digest,
        },
        "renderDocumentDigest": command.command.render_document_digest,
        "resourceCount": document.resources().len(),
        "resourceFetchCount": 0,
        "terminalFrameCount": terminal.frames().len(),
        "terminalFrameTypes": ["RESULT_METADATA", "RESULT_IMAGE"],
        "metadata": metadata,
        "imageArtifact": {
            "path": file_name(&paths.image),
            "sha256": artifact_sha256,
            "byteLength": image_bytes.len(),
        },
        "boundary": {
            "networkAttempts": 0,
            "externalProviderAttempts": 0,
            "rendererProfileRegistered": false,
            "formalRecordsIssued": 0,
            "recordIssuanceAllowed": false,
            "executionClassExecutable": false,
        },
    });
    let mut report_bytes =
        serde_json::to_vec_pretty(&report).expect("Rust execution report must serialize");
    report_bytes.push(b'\n');
    write_new(&paths.report, &report_bytes);
}

fn evidence_paths() -> Option<EvidencePaths> {
    let command = env::var_os("RENDERWEAVE_RENDERING_PIPELINE_COMMAND");
    let report = env::var_os("RENDERWEAVE_RENDERING_PIPELINE_RUST_REPORT");
    let image = env::var_os("RENDERWEAVE_RENDERING_PIPELINE_IMAGE");
    match (command, report, image) {
        (None, None, None) => None,
        (Some(command), Some(report), Some(image)) => Some(EvidencePaths {
            command: PathBuf::from(command),
            report: PathBuf::from(report),
            image: PathBuf::from(image),
        }),
        _ => panic!("Rendering Pipeline Rust evidence paths must be supplied together"),
    }
}

fn write_new(path: &Path, bytes: &[u8]) {
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .expect("execution evidence must be write-once");
    output
        .write_all(bytes)
        .expect("execution evidence must be complete");
}

fn file_name(path: &Path) -> String {
    path.file_name()
        .and_then(|name| name.to_str())
        .expect("evidence path must have a UTF-8 leaf")
        .to_owned()
}

struct EvidencePaths {
    command: PathBuf,
    report: PathBuf,
    image: PathBuf,
}

struct NoFetch;

impl ResourceFetcher for NoFetch {
    fn fetch_resource(
        &self,
        _target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        _state: &mut RequestResourceFetchState,
    ) -> Result<FetchedResource, ResourceFetchProblem> {
        panic!("resource-free Rendering Pipeline target must not fetch")
    }
}
