#![cfg(feature = "native-jpeg-turbo")]

use std::sync::Mutex;

use renderweave_renderer_document::validate_render_document;
use renderweave_renderer_engine::{
    EngineCheckpoint, EngineExecutionControl, EngineInterruption, EngineProblemCode,
    EngineProblemStage, render_jpeg, render_jpeg_controlled,
};
use serde_json::Value;

const ENGINE_VECTORS: &str = include_str!("../../../engine-png-vectors-v1.json");
const JPEG_VECTORS: &str = include_str!("../../../output-jpeg-vectors-v1.json");

#[test]
fn engine_reuses_the_canonical_surface_and_emits_the_exact_jpeg_profile() {
    let engine: Value = serde_json::from_str(ENGINE_VECTORS).expect("Engine vectors");
    let jpeg: Value = serde_json::from_str(JPEG_VECTORS).expect("JPEG vectors");
    let document_json = serde_json::to_string(&engine["documents"]["transparent1x1"])
        .expect("canonical document JSON");
    let document = validate_render_document(&document_json).expect("admitted document");
    let expected = jpeg["jpegCases"]
        .as_array()
        .expect("JPEG cases")
        .iter()
        .find(|case| case["id"] == "transparent-white-matte-1x1-q90")
        .expect("1x1 white matte golden");

    let output = render_jpeg(&document, 96, 90).expect("exact JPEG Engine path");

    assert_eq!(1, output.width_px());
    assert_eq!(1, output.height_px());
    assert_eq!(96, output.dpi());
    assert_eq!(90, output.quality());
    assert_eq!("image/jpeg", output.media_type());
    assert_eq!("renderweave-output-jpeg/1.0", output.output_profile());
    assert_eq!(
        engine["renderedCases"][0]["expected"]["pixelSha256"],
        output.pixel_sha256()
    );
    assert_eq!(
        expected["expected"]["byteLength"].as_u64().unwrap(),
        output.byte_length() as u64
    );
    assert_eq!(
        expected["expected"]["sha256"].as_str().unwrap(),
        output.content_sha256()
    );
    assert_eq!(
        expected["expected"]["entropyHex"].as_str().unwrap(),
        hex::encode(entropy_bytes(output.bytes()))
    );
}

fn entropy_bytes(encoded: &[u8]) -> &[u8] {
    let mut offset = 2_usize;
    while offset + 4 <= encoded.len() {
        assert_eq!(0xff, encoded[offset]);
        let marker = encoded[offset + 1];
        let length = u16::from_be_bytes([encoded[offset + 2], encoded[offset + 3]]) as usize;
        if marker == 0xda {
            return &encoded[offset + 2 + length..encoded.len() - 2];
        }
        offset += 2 + length;
    }
    panic!("JPEG SOS is absent")
}

#[test]
fn engine_deadline_during_jpeg_encoding_never_reaches_output_seal() {
    let engine: Value = serde_json::from_str(ENGINE_VECTORS).expect("Engine vectors");
    let document_json = serde_json::to_string(&engine["documents"]["transparent1x1"])
        .expect("canonical document JSON");
    let document = validate_render_document(&document_json).expect("admitted document");
    let control = InterruptEncoding {
        observed: Mutex::new(Vec::new()),
    };

    let problem = render_jpeg_controlled(&document, 96, 90, &control)
        .expect_err("deadline must discard the unsealed JPEG");

    assert_eq!(
        EngineProblemCode::RenderDeadlineExceeded,
        problem.problem_code()
    );
    assert_eq!(EngineProblemStage::Encoding, problem.problem_stage());
    let observed = control.observed.lock().expect("checkpoint observations");
    assert!(observed.contains(&EngineCheckpoint::Encoding));
    assert!(!observed.contains(&EngineCheckpoint::OutputSeal));
}

struct InterruptEncoding {
    observed: Mutex<Vec<EngineCheckpoint>>,
}

impl EngineExecutionControl for InterruptEncoding {
    fn checkpoint(&self, checkpoint: EngineCheckpoint) -> Result<(), EngineInterruption> {
        self.observed
            .lock()
            .expect("checkpoint observations")
            .push(checkpoint);
        if checkpoint == EngineCheckpoint::Encoding {
            Err(EngineInterruption::DeadlineExceeded)
        } else {
            Ok(())
        }
    }
}
