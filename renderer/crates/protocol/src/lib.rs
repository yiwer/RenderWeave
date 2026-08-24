//! Exact codec and closed payloads for `renderweave-renderer-process/1.0`.
//!
//! This crate is deliberately independent of layout, resource fetching, raster, and encoding.
//! It admits bytes at the process boundary and atomically seals already-encoded image bytes into
//! the closed result payload pair without interpreting or manufacturing image content.

use serde::de::{DeserializeOwned, Error as DeError, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::value::RawValue;
use serde_json::{Map, Number, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::{Display, Formatter};
use std::io::{self, Read, Write};

pub const PROCESS_CONTRACT_VERSION: &str = "renderweave-renderer-process/1.0";
pub const COMMAND_CONTRACT_VERSION: &str = "renderweave-render-command/1.0";
pub const CANCEL_CONTRACT_VERSION: &str = "renderweave-render-cancel/1.0";
pub const PROBLEM_CONTRACT_VERSION: &str = "renderweave-render-problem/1.0";
pub const RESULT_CONTRACT_VERSION: &str = "renderweave-render-result/1.0";
pub const PROCESS_MANIFEST_VERSION: &str = "renderweave-renderer-process-manifest/1.0";
pub const COMMAND_DIGEST_DOMAIN: &[u8] = b"renderweave-render-command/1\0";
pub const DOCUMENT_DIGEST_DOMAIN: &[u8] = b"renderweave-render-document/1\0";
pub const PROTOCOL_CAPABILITIES: [&str; 5] = [
    "render-command-v1",
    "render-cancel-v1",
    "render-document-v1",
    "render-result-v1",
    "render-problem-v1",
];

#[derive(Debug)]
pub enum ProtocolError {
    Io(io::Error),
    Invalid(&'static str),
    InvalidOwned(String),
}

impl Display for ProtocolError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "renderer protocol I/O failed: {error}"),
            Self::Invalid(message) => formatter.write_str(message),
            Self::InvalidOwned(message) => formatter.write_str(message),
        }
    }
}

impl std::error::Error for ProtocolError {}

impl From<io::Error> for ProtocolError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<serde_json::Error> for ProtocolError {
    fn from(value: serde_json::Error) -> Self {
        Self::InvalidOwned(format!("strict JSON rejected: {value}"))
    }
}

#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FrameType {
    ClientHello = 0x01,
    ServerHello = 0x02,
    Command = 0x10,
    Cancel = 0x11,
    ResultMetadata = 0x20,
    ResultImage = 0x21,
    Problem = 0x30,
}

impl TryFrom<u8> for FrameType {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0x01 => Ok(Self::ClientHello),
            0x02 => Ok(Self::ServerHello),
            0x10 => Ok(Self::Command),
            0x11 => Ok(Self::Cancel),
            0x20 => Ok(Self::ResultMetadata),
            0x21 => Ok(Self::ResultImage),
            0x30 => Ok(Self::Problem),
            _ => Err(ProtocolError::Invalid("unknown renderer frame type")),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Frame {
    pub frame_type: FrameType,
    pub payload: Vec<u8>,
}

pub fn encode_frame(frame_type: FrameType, payload: &[u8]) -> Result<Vec<u8>, ProtocolError> {
    let framed_length = payload
        .len()
        .checked_add(1)
        .ok_or(ProtocolError::Invalid("renderer frame length overflow"))?;
    let framed_length = u32::try_from(framed_length)
        .map_err(|_| ProtocolError::Invalid("renderer frame exceeds uint32 length"))?;
    let mut encoded = Vec::with_capacity(4 + framed_length as usize);
    encoded.extend_from_slice(&framed_length.to_be_bytes());
    encoded.push(frame_type as u8);
    encoded.extend_from_slice(payload);
    Ok(encoded)
}

pub fn write_frame(
    writer: &mut impl Write,
    frame_type: FrameType,
    payload: &[u8],
) -> Result<(), ProtocolError> {
    writer.write_all(&encode_frame(frame_type, payload)?)?;
    writer.flush()?;
    Ok(())
}

pub fn read_frame(
    reader: &mut impl Read,
    maximum_framed_bytes: usize,
) -> Result<Frame, ProtocolError> {
    if maximum_framed_bytes == 0 {
        return Err(ProtocolError::Invalid(
            "maximum framed bytes must be positive",
        ));
    }
    let mut header = [0_u8; 4];
    reader.read_exact(&mut header)?;
    let framed_length = u32::from_be_bytes(header) as usize;
    if framed_length == 0 {
        return Err(ProtocolError::Invalid(
            "renderer frame length must include a type byte",
        ));
    }
    if framed_length > maximum_framed_bytes {
        return Err(ProtocolError::Invalid(
            "renderer frame exceeds configured maximum",
        ));
    }
    let mut body = vec![0_u8; framed_length];
    reader.read_exact(&mut body)?;
    Ok(Frame {
        frame_type: FrameType::try_from(body[0])?,
        payload: body[1..].to_vec(),
    })
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientHello {
    pub contract_version: String,
    pub manifest_sha256: String,
    pub required_capabilities: Vec<String>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServerHello {
    pub contract_version: String,
    pub manifest_sha256: String,
    pub capabilities: Vec<String>,
    pub renderer_profiles: Vec<String>,
    pub profile_availability: String,
    pub certification_status: String,
}

pub fn parse_client_hello(
    bytes: &[u8],
    expected_manifest_sha256: &str,
) -> Result<ClientHello, ProtocolError> {
    let hello: ClientHello = parse_canonical(bytes)?;
    require_exact(
        &hello.contract_version,
        PROCESS_CONTRACT_VERSION,
        "client process contract mismatch",
    )?;
    require_sha256(&hello.manifest_sha256)?;
    if hello.manifest_sha256 != expected_manifest_sha256 {
        return Err(ProtocolError::Invalid("client manifest identity mismatch"));
    }
    if hello.required_capabilities != protocol_capabilities() {
        return Err(ProtocolError::Invalid(
            "client required capability set or order mismatch",
        ));
    }
    Ok(hello)
}

pub fn client_hello_bytes(manifest_sha256: &str) -> Result<Vec<u8>, ProtocolError> {
    require_sha256(manifest_sha256)?;
    Ok(serde_json::to_vec(&ClientHello {
        contract_version: PROCESS_CONTRACT_VERSION.to_owned(),
        manifest_sha256: manifest_sha256.to_owned(),
        required_capabilities: protocol_capabilities(),
    })?)
}

pub fn server_hello_bytes(identity: &ManifestIdentity) -> Result<Vec<u8>, ProtocolError> {
    let hello = ServerHello {
        contract_version: PROCESS_CONTRACT_VERSION.to_owned(),
        manifest_sha256: identity.manifest_sha256.clone(),
        capabilities: protocol_capabilities(),
        renderer_profiles: identity.renderer_profiles.clone(),
        profile_availability: identity.profile_availability.clone(),
        certification_status: identity.certification_status.clone(),
    };
    Ok(serde_json::to_vec(&hello)?)
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RendererCommand {
    pub contract_version: String,
    pub request_id: String,
    pub renderer_profile: String,
    pub deadline_at: String,
    pub render_document_digest: String,
    pub document: Box<RawValue>,
    pub output: OutputSelection,
    pub diagnostics: Diagnostics,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(untagged)]
pub enum OutputSelection {
    Png(PngOutput),
    Jpeg(JpegOutput),
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PngOutput {
    pub profile: String,
    pub dpi: u32,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JpegOutput {
    pub profile: String,
    pub dpi: u32,
    pub quality: u8,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Diagnostics {
    pub layout_trace: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ResultOutputSelection {
    Png { dpi: u32 },
    Jpeg { dpi: u32, quality: u8 },
}

pub struct ResultSealInput<'a> {
    pub request_id: &'a str,
    pub renderer_profile: &'a str,
    pub dsl_version: &'a str,
    pub layout_profile: &'a str,
    pub width_px: u32,
    pub height_px: u32,
    pub output: ResultOutputSelection,
    pub image_bytes: Vec<u8>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ResultMetadata<'a> {
    contract_version: &'static str,
    request_id: &'a str,
    renderer_profile: &'a str,
    dsl_version: &'a str,
    layout_profile: &'a str,
    output_profile: &'static str,
    format: &'static str,
    media_type: &'static str,
    width_px: u32,
    height_px: u32,
    dpi: u32,
    byte_length: u64,
    content_sha256: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    quality: Option<u8>,
}

pub struct SealedResult {
    request_id: Box<str>,
    format: &'static str,
    byte_length: u64,
    content_sha256: Box<str>,
    metadata_payload: Vec<u8>,
    image_payload: Vec<u8>,
}

impl std::fmt::Debug for SealedResult {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("SealedResult")
            .field("request_id", &self.request_id)
            .field("format", &self.format)
            .field("byte_length", &self.byte_length)
            .field("content_sha256", &self.content_sha256)
            .finish_non_exhaustive()
    }
}

impl SealedResult {
    pub fn metadata_payload(&self) -> &[u8] {
        &self.metadata_payload
    }

    pub fn image_payload(&self) -> &[u8] {
        &self.image_payload
    }

    pub const fn byte_length(&self) -> u64 {
        self.byte_length
    }

    pub fn content_sha256(&self) -> &str {
        &self.content_sha256
    }
}

pub fn seal_result(input: ResultSealInput<'_>) -> Result<SealedResult, ProtocolError> {
    require_uuid_v4(input.request_id)?;
    require_exact(
        input.renderer_profile,
        "renderweave-renderer/1.0",
        "result renderer profile mismatch",
    )?;
    require_exact(
        input.dsl_version,
        "renderweave-render/1.0",
        "result DSL version mismatch",
    )?;
    require_exact(
        input.layout_profile,
        "renderweave-layout/1.0",
        "result layout profile mismatch",
    )?;
    if input.width_px == 0 || input.height_px == 0 {
        return Err(ProtocolError::Invalid(
            "result pixel dimensions must be positive",
        ));
    }
    if input.image_bytes.is_empty() {
        return Err(ProtocolError::Invalid(
            "result image bytes must be nonempty",
        ));
    }
    let byte_length = u64::try_from(input.image_bytes.len())
        .map_err(|_| ProtocolError::Invalid("result image length exceeds uint64"))?;
    if byte_length > i64::MAX as u64 {
        return Err(ProtocolError::Invalid(
            "result image length exceeds signed transport range",
        ));
    }

    let (output_profile, format, media_type, dpi, quality) = match input.output {
        ResultOutputSelection::Png { dpi } if dpi > 0 => {
            ("renderweave-output-png/1.0", "PNG", "image/png", dpi, None)
        }
        ResultOutputSelection::Jpeg { dpi, quality } if dpi > 0 && (1..=100).contains(&quality) => {
            (
                "renderweave-output-jpeg/1.0",
                "JPEG",
                "image/jpeg",
                dpi,
                Some(quality),
            )
        }
        ResultOutputSelection::Png { .. } => {
            return Err(ProtocolError::Invalid("result PNG dpi must be positive"));
        }
        ResultOutputSelection::Jpeg { .. } => {
            return Err(ProtocolError::Invalid(
                "result JPEG dpi/quality is outside the closed shape",
            ));
        }
    };

    let content_sha256 = raw_sha256(&input.image_bytes);
    let metadata_payload = serde_json::to_vec(&ResultMetadata {
        contract_version: RESULT_CONTRACT_VERSION,
        request_id: input.request_id,
        renderer_profile: input.renderer_profile,
        dsl_version: input.dsl_version,
        layout_profile: input.layout_profile,
        output_profile,
        format,
        media_type,
        width_px: input.width_px,
        height_px: input.height_px,
        dpi,
        byte_length,
        content_sha256: &content_sha256,
        quality,
    })?;

    let image_length = input.image_bytes.len();
    let image_payload_length = image_length.checked_add(16).ok_or(ProtocolError::Invalid(
        "result image payload length overflow",
    ))?;
    let mut image_payload = input.image_bytes;
    image_payload
        .try_reserve_exact(16)
        .map_err(|_| ProtocolError::Invalid("result image payload allocation failed"))?;
    image_payload.resize(image_payload_length, 0);
    image_payload.copy_within(0..image_length, 16);
    image_payload[..16].copy_from_slice(&uuid_network_bytes(input.request_id)?);

    Ok(SealedResult {
        request_id: input.request_id.into(),
        format,
        byte_length,
        content_sha256: content_sha256.into_boxed_str(),
        metadata_payload,
        image_payload,
    })
}

#[derive(Debug)]
pub struct AdmittedCommand {
    pub command: RendererCommand,
    pub canonical_bytes: Vec<u8>,
    pub command_digest: String,
    pub deadline_epoch_millis: i64,
}

pub fn parse_command(bytes: &[u8]) -> Result<AdmittedCommand, ProtocolError> {
    let command: RendererCommand = parse_canonical(bytes)?;
    require_exact(
        &command.contract_version,
        COMMAND_CONTRACT_VERSION,
        "renderer Command contract mismatch",
    )?;
    require_uuid_v4(&command.request_id)?;
    require_profile(&command.renderer_profile)?;
    require_sha256(&command.render_document_digest)?;
    let deadline_epoch_millis = parse_deadline_millis(&command.deadline_at)?;

    let strict_document: StrictValue = serde_json::from_str(command.document.get())?;
    if !strict_document.0.is_object() {
        return Err(ProtocolError::Invalid(
            "RenderDocument must be one JSON object",
        ));
    }
    let canonical_document = serde_json::to_string(&strict_document.0)?;
    if canonical_document.as_bytes() != command.document.get().as_bytes() {
        return Err(ProtocolError::Invalid(
            "RenderDocument bytes are not canonical",
        ));
    }
    let actual_document_digest =
        digest_with_domain(DOCUMENT_DIGEST_DOMAIN, command.document.get().as_bytes());
    if actual_document_digest != command.render_document_digest {
        return Err(ProtocolError::Invalid(
            "renderDocumentDigest does not match exact document bytes",
        ));
    }
    validate_output(&command.output)?;

    Ok(AdmittedCommand {
        command,
        canonical_bytes: bytes.to_vec(),
        command_digest: digest_with_domain(COMMAND_DIGEST_DOMAIN, bytes),
        deadline_epoch_millis,
    })
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Cancel {
    pub contract_version: String,
    pub request_id: String,
    pub renderer_command_digest: String,
    pub deadline_at: String,
}

#[derive(Debug)]
pub struct AdmittedCancel {
    pub cancel: Cancel,
    pub deadline_epoch_millis: i64,
}

pub fn parse_cancel(bytes: &[u8]) -> Result<AdmittedCancel, ProtocolError> {
    let cancel: Cancel = parse_canonical(bytes)?;
    require_exact(
        &cancel.contract_version,
        CANCEL_CONTRACT_VERSION,
        "renderer cancel contract mismatch",
    )?;
    require_uuid_v4(&cancel.request_id)?;
    require_sha256(&cancel.renderer_command_digest)?;
    let deadline_epoch_millis = parse_deadline_millis(&cancel.deadline_at)?;
    Ok(AdmittedCancel {
        cancel,
        deadline_epoch_millis,
    })
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EngineStage {
    CommandAdmission,
    RequestControl,
    DocumentAdmission,
    OutputPreflight,
    ResourcePreparation,
    Layout,
    Shaping,
    Rasterization,
    Encoding,
    TraceProjection,
    OutputSeal,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Problem {
    pub contract_version: String,
    pub request_id: String,
    pub code: String,
    pub engine_stage: EngineStage,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub occurrence_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resource_id: Option<String>,
    pub parameters: BTreeMap<String, String>,
}

pub fn problem_bytes(
    request_id: &str,
    code: &str,
    engine_stage: EngineStage,
) -> Result<Vec<u8>, ProtocolError> {
    require_uuid_v4(request_id)?;
    require_problem_code(code)?;
    serialize_problem(request_id, code, engine_stage, None, BTreeMap::new())
}

pub fn resource_problem_bytes(
    request_id: &str,
    code: &str,
    engine_stage: EngineStage,
    resource_id: &str,
) -> Result<Vec<u8>, ProtocolError> {
    require_uuid_v4(request_id)?;
    require_problem_code(code)?;
    require_resource_id(resource_id)?;
    serialize_problem(
        request_id,
        code,
        engine_stage,
        Some(resource_id),
        BTreeMap::new(),
    )
}

pub fn resource_limit_problem_bytes(
    request_id: &str,
    code: &str,
    engine_stage: EngineStage,
    resource_id: &str,
    limit_id: &str,
) -> Result<Vec<u8>, ProtocolError> {
    require_uuid_v4(request_id)?;
    require_problem_code(code)?;
    require_resource_id(resource_id)?;
    if code != "RESOURCE_BUDGET_EXCEEDED" || !is_canonical_limit_id(limit_id) {
        return Err(ProtocolError::Invalid(
            "resource capacity problem shape is invalid",
        ));
    }
    serialize_problem(
        request_id,
        code,
        engine_stage,
        Some(resource_id),
        BTreeMap::from([("limitId".to_owned(), limit_id.to_owned())]),
    )
}

fn serialize_problem(
    request_id: &str,
    code: &str,
    engine_stage: EngineStage,
    resource_id: Option<&str>,
    parameters: BTreeMap<String, String>,
) -> Result<Vec<u8>, ProtocolError> {
    Ok(serde_json::to_vec(&Problem {
        contract_version: PROBLEM_CONTRACT_VERSION.to_owned(),
        request_id: request_id.to_owned(),
        code: code.to_owned(),
        engine_stage,
        occurrence_id: None,
        resource_id: resource_id.map(str::to_owned),
        parameters,
    })?)
}

#[derive(Clone, Debug)]
pub struct ManifestIdentity {
    pub manifest_sha256: String,
    pub renderer_profiles: Vec<String>,
    pub profile_availability: String,
    pub certification_status: String,
}

pub fn validate_process_manifest(bytes: &[u8]) -> Result<ManifestIdentity, ProtocolError> {
    let strict: StrictValue = serde_json::from_slice(bytes)?;
    let object = strict
        .0
        .as_object()
        .ok_or(ProtocolError::Invalid("process manifest must be an object"))?;
    let expected_members: BTreeSet<&str> = [
        "manifestVersion",
        "processContractVersion",
        "frameTypes",
        "protocolCapabilities",
        "rustToolchain",
        "rustEdition",
        "cargoLockSha256",
        "vendorTreeSha256",
        "vendorFileCount",
        "directDependencies",
        "supportedProductionTargets",
        "rendererProfiles",
        "profileAvailability",
        "certificationStatus",
        "rasterImplementation",
        "physicalCertificationRecords",
    ]
    .into_iter()
    .collect();
    let actual_members: BTreeSet<&str> = object.keys().map(String::as_str).collect();
    if actual_members != expected_members {
        return Err(ProtocolError::Invalid(
            "process manifest member set mismatch",
        ));
    }
    require_manifest_string(object, "manifestVersion", PROCESS_MANIFEST_VERSION)?;
    require_manifest_string(object, "processContractVersion", PROCESS_CONTRACT_VERSION)?;
    require_manifest_string(object, "profileAvailability", "NOT_REGISTERED")?;
    require_manifest_string(object, "certificationStatus", "NOT_CERTIFIED")?;
    require_manifest_string(object, "rasterImplementation", "ABSENT")?;
    let capabilities = string_list(object, "protocolCapabilities")?;
    if capabilities != protocol_capabilities() {
        return Err(ProtocolError::Invalid(
            "process manifest capability set or order mismatch",
        ));
    }
    validate_frame_table(object)?;
    let renderer_profiles = string_list(object, "rendererProfiles")?;
    if !renderer_profiles.is_empty() {
        return Err(ProtocolError::Invalid(
            "T22 process manifest must not register renderer profiles",
        ));
    }
    let physical_records = object
        .get("physicalCertificationRecords")
        .and_then(Value::as_array)
        .ok_or(ProtocolError::Invalid(
            "physicalCertificationRecords must be an array",
        ))?;
    if !physical_records.is_empty() {
        return Err(ProtocolError::Invalid(
            "T22 process manifest must not claim physical certification",
        ));
    }
    for field in ["cargoLockSha256", "vendorTreeSha256"] {
        require_sha256(
            object
                .get(field)
                .and_then(Value::as_str)
                .ok_or(ProtocolError::Invalid("manifest digest must be a string"))?,
        )?;
    }
    Ok(ManifestIdentity {
        manifest_sha256: raw_sha256_prefixed(bytes),
        renderer_profiles,
        profile_availability: "NOT_REGISTERED".to_owned(),
        certification_status: "NOT_CERTIFIED".to_owned(),
    })
}

pub fn raw_sha256(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

pub fn raw_sha256_prefixed(bytes: &[u8]) -> String {
    format!("sha256:{}", raw_sha256(bytes))
}

pub fn digest_with_domain(domain: &[u8], bytes: &[u8]) -> String {
    let mut digest = Sha256::new();
    digest.update(domain);
    digest.update(bytes);
    format!("sha256:{}", hex::encode(digest.finalize()))
}

fn parse_canonical<T>(bytes: &[u8]) -> Result<T, ProtocolError>
where
    T: DeserializeOwned + Serialize,
{
    let value: T = serde_json::from_slice(bytes)?;
    let canonical = serde_json::to_vec(&value)?;
    if canonical != bytes {
        return Err(ProtocolError::Invalid("JSON payload is not canonical"));
    }
    Ok(value)
}

fn validate_output(output: &OutputSelection) -> Result<(), ProtocolError> {
    match output {
        OutputSelection::Png(png) => {
            require_exact(
                &png.profile,
                "renderweave-output-png/1.0",
                "PNG output profile mismatch",
            )?;
            if png.dpi == 0 {
                return Err(ProtocolError::Invalid("PNG dpi must be positive"));
            }
        }
        OutputSelection::Jpeg(jpeg) => {
            require_exact(
                &jpeg.profile,
                "renderweave-output-jpeg/1.0",
                "JPEG output profile mismatch",
            )?;
            if jpeg.dpi == 0 || !(1..=100).contains(&jpeg.quality) {
                return Err(ProtocolError::Invalid(
                    "JPEG dpi/quality is outside the closed shape",
                ));
            }
        }
    }
    Ok(())
}

fn require_exact(actual: &str, expected: &str, message: &'static str) -> Result<(), ProtocolError> {
    if actual != expected {
        return Err(ProtocolError::Invalid(message));
    }
    Ok(())
}

fn require_uuid_v4(value: &str) -> Result<(), ProtocolError> {
    let bytes = value.as_bytes();
    if bytes.len() != 36
        || ![8, 13, 18, 23]
            .into_iter()
            .all(|index| bytes[index] == b'-')
        || bytes[14] != b'4'
        || !matches!(bytes[19], b'8' | b'9' | b'a' | b'b')
    {
        return Err(ProtocolError::Invalid(
            "requestId must be a canonical lowercase UUID v4",
        ));
    }
    for (index, byte) in bytes.iter().enumerate() {
        if [8, 13, 18, 23].contains(&index) {
            continue;
        }
        if !matches!(byte, b'0'..=b'9' | b'a'..=b'f') {
            return Err(ProtocolError::Invalid(
                "requestId must be a canonical lowercase UUID v4",
            ));
        }
    }
    Ok(())
}

fn uuid_network_bytes(value: &str) -> Result<[u8; 16], ProtocolError> {
    require_uuid_v4(value)?;
    let mut output = [0_u8; 16];
    let mut nibble_index = 0_usize;
    for byte in value.bytes().filter(|byte| *byte != b'-') {
        let nibble = match byte {
            b'0'..=b'9' => byte - b'0',
            b'a'..=b'f' => byte - b'a' + 10,
            _ => unreachable!("canonical UUID validation admits lowercase hexadecimal only"),
        };
        let output_index = nibble_index / 2;
        if nibble_index.is_multiple_of(2) {
            output[output_index] = nibble << 4;
        } else {
            output[output_index] |= nibble;
        }
        nibble_index += 1;
    }
    debug_assert_eq!(nibble_index, 32);
    Ok(output)
}

fn require_sha256(value: &str) -> Result<(), ProtocolError> {
    if value.len() != 71 || !value.starts_with("sha256:") {
        return Err(ProtocolError::Invalid(
            "digest must be sha256: plus 64 lowercase hex chars",
        ));
    }
    if !value.as_bytes()[7..]
        .iter()
        .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(ProtocolError::Invalid(
            "digest must be sha256: plus 64 lowercase hex chars",
        ));
    }
    Ok(())
}

fn require_profile(value: &str) -> Result<(), ProtocolError> {
    let bytes = value.as_bytes();
    if bytes.is_empty()
        || bytes.len() > 256
        || !bytes[0].is_ascii_alphanumeric()
        || !bytes
            .iter()
            .all(|byte| matches!(byte, b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'_' | b'/' | b'-'))
    {
        return Err(ProtocolError::Invalid(
            "renderer profile identity is not canonical",
        ));
    }
    Ok(())
}

fn require_problem_code(code: &str) -> Result<(), ProtocolError> {
    match code {
        "RENDER_INTERNAL_ERROR"
        | "RENDER_REQUEST_CONFLICT"
        | "RENDER_REQUEST_STATE_LOST"
        | "RENDER_CANCELLED"
        | "RENDER_DEADLINE_EXCEEDED"
        | "RENDER_ENGINE_BUSY"
        | "RESOURCE_LEASE_EXPIRED"
        | "RESOURCE_BUDGET_EXCEEDED"
        | "FETCH_FAILED"
        | "LENGTH_MISMATCH"
        | "HASH_MISMATCH"
        | "MEDIA_MISMATCH"
        | "DECODE_FAILED"
        | "RENDER_LAYOUT_TRACE_LIMIT_EXCEEDED" => Ok(()),
        _ => Err(ProtocolError::Invalid(
            "problem code is not in the closed catalog",
        )),
    }
}

fn is_canonical_limit_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value
            .as_bytes()
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn require_resource_id(resource_id: &str) -> Result<(), ProtocolError> {
    let bytes = resource_id.as_bytes();
    if bytes.len() != 70
        || !bytes.starts_with(b"rwres_")
        || !bytes[6..]
            .iter()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(ProtocolError::Invalid(
            "problem resourceId is not a canonical RenderResource identity",
        ));
    }
    Ok(())
}

fn parse_deadline_millis(value: &str) -> Result<i64, ProtocolError> {
    let bytes = value.as_bytes();
    if bytes.len() != 24
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || bytes[19] != b'.'
        || bytes[23] != b'Z'
    {
        return Err(ProtocolError::Invalid(
            "deadlineAt must be RFC3339 UTC with exactly three milliseconds",
        ));
    }
    let year = digits(bytes, 0, 4)? as i64;
    let month = digits(bytes, 5, 2)? as i64;
    let day = digits(bytes, 8, 2)? as i64;
    let hour = digits(bytes, 11, 2)? as i64;
    let minute = digits(bytes, 14, 2)? as i64;
    let second = digits(bytes, 17, 2)? as i64;
    let millis = digits(bytes, 20, 3)? as i64;
    if year == 0
        || !(1..=12).contains(&month)
        || day == 0
        || day > days_in_month(year, month)
        || hour > 23
        || minute > 59
        || second > 59
    {
        return Err(ProtocolError::Invalid(
            "deadlineAt contains an invalid UTC date",
        ));
    }
    let days = days_from_civil(year, month, day);
    days.checked_mul(86_400_000)
        .and_then(|base| base.checked_add(hour * 3_600_000))
        .and_then(|base| base.checked_add(minute * 60_000))
        .and_then(|base| base.checked_add(second * 1_000))
        .and_then(|base| base.checked_add(millis))
        .ok_or(ProtocolError::Invalid("deadlineAt epoch value overflow"))
}

fn digits(bytes: &[u8], start: usize, length: usize) -> Result<u32, ProtocolError> {
    let mut value = 0_u32;
    for byte in &bytes[start..start + length] {
        if !byte.is_ascii_digit() {
            return Err(ProtocolError::Invalid("deadlineAt contains a non-digit"));
        }
        value = value * 10 + u32::from(*byte - b'0');
    }
    Ok(value)
}

fn days_in_month(year: i64, month: i64) -> i64 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if year % 400 == 0 || (year % 4 == 0 && year % 100 != 0) => 29,
        2 => 28,
        _ => 0,
    }
}

fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let adjusted_year = year - i64::from(month <= 2);
    let era = adjusted_year.div_euclid(400);
    let year_of_era = adjusted_year - era * 400;
    let shifted_month = month + if month > 2 { -3 } else { 9 };
    let day_of_year = (153 * shifted_month + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

fn protocol_capabilities() -> Vec<String> {
    PROTOCOL_CAPABILITIES
        .iter()
        .map(|value| (*value).to_owned())
        .collect()
}

fn require_manifest_string(
    object: &Map<String, Value>,
    field: &'static str,
    expected: &'static str,
) -> Result<(), ProtocolError> {
    let value = object
        .get(field)
        .and_then(Value::as_str)
        .ok_or(ProtocolError::Invalid("manifest string field is absent"))?;
    require_exact(value, expected, "process manifest identity mismatch")
}

fn string_list(
    object: &Map<String, Value>,
    field: &'static str,
) -> Result<Vec<String>, ProtocolError> {
    object
        .get(field)
        .and_then(Value::as_array)
        .ok_or(ProtocolError::Invalid("manifest list field is absent"))?
        .iter()
        .map(|value| {
            value
                .as_str()
                .map(str::to_owned)
                .ok_or(ProtocolError::Invalid(
                    "manifest list member must be a string",
                ))
        })
        .collect()
}

fn validate_frame_table(object: &Map<String, Value>) -> Result<(), ProtocolError> {
    let actual =
        object
            .get("frameTypes")
            .and_then(Value::as_object)
            .ok_or(ProtocolError::Invalid(
                "manifest frameTypes must be an object",
            ))?;
    let expected = [
        ("CLIENT_HELLO", 0x01_u64),
        ("SERVER_HELLO", 0x02),
        ("COMMAND", 0x10),
        ("CANCEL", 0x11),
        ("RESULT_METADATA", 0x20),
        ("RESULT_IMAGE", 0x21),
        ("PROBLEM", 0x30),
    ];
    if actual.len() != expected.len()
        || expected
            .iter()
            .any(|(name, value)| actual.get(*name).and_then(Value::as_u64) != Some(*value))
    {
        return Err(ProtocolError::Invalid(
            "process manifest frame table mismatch",
        ));
    }
    Ok(())
}

struct StrictValue(Value);

impl<'de> Deserialize<'de> for StrictValue {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        deserializer.deserialize_any(StrictValueVisitor)
    }
}

struct StrictValueVisitor;

impl<'de> Visitor<'de> for StrictValueVisitor {
    type Value = StrictValue;

    fn expecting(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("strict JSON without duplicate members or null")
    }

    fn visit_unit<E>(self) -> Result<Self::Value, E>
    where
        E: DeError,
    {
        Err(E::custom("null is forbidden"))
    }

    fn visit_bool<E>(self, value: bool) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::Bool(value)))
    }

    fn visit_i64<E>(self, value: i64) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::Number(Number::from(value))))
    }

    fn visit_u64<E>(self, value: u64) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::Number(Number::from(value))))
    }

    fn visit_f64<E>(self, value: f64) -> Result<Self::Value, E>
    where
        E: DeError,
    {
        Number::from_f64(value)
            .map(Value::Number)
            .map(StrictValue)
            .ok_or_else(|| E::custom("non-finite number is forbidden"))
    }

    fn visit_str<E>(self, value: &str) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::String(value.to_owned())))
    }

    fn visit_string<E>(self, value: String) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::String(value)))
    }

    fn visit_seq<A>(self, mut sequence: A) -> Result<Self::Value, A::Error>
    where
        A: SeqAccess<'de>,
    {
        let mut values = Vec::new();
        while let Some(value) = sequence.next_element::<StrictValue>()? {
            values.push(value.0);
        }
        Ok(StrictValue(Value::Array(values)))
    }

    fn visit_map<A>(self, mut object: A) -> Result<Self::Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut values = Map::new();
        while let Some((key, value)) = object.next_entry::<String, StrictValue>()? {
            if values.insert(key, value.0).is_some() {
                return Err(A::Error::custom("duplicate object member"));
            }
        }
        Ok(StrictValue(Value::Object(values)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;
    use std::io::Cursor;

    const VECTORS: &str = include_str!("../../../protocol-vectors-v1.json");
    const PROCESS_MANIFEST: &[u8] = include_bytes!("../../../process-manifest.json");

    #[test]
    fn frozen_process_identity_and_frame_table() {
        assert_eq!(PROCESS_CONTRACT_VERSION, "renderweave-renderer-process/1.0");
        assert_eq!(FrameType::ClientHello as u8, 0x01);
        assert_eq!(FrameType::ServerHello as u8, 0x02);
        assert_eq!(FrameType::Command as u8, 0x10);
        assert_eq!(FrameType::Cancel as u8, 0x11);
        assert_eq!(FrameType::ResultMetadata as u8, 0x20);
        assert_eq!(FrameType::ResultImage as u8, 0x21);
        assert_eq!(FrameType::Problem as u8, 0x30);
    }

    #[test]
    fn exact_frames_match_shared_cross_language_vectors() {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        for case in vectors["cases"].as_array().unwrap() {
            let payload = match case.get("canonicalJson") {
                Some(value) => value.as_str().unwrap().as_bytes().to_vec(),
                None => decode_base64(case["payloadBase64"].as_str().unwrap()),
            };
            let frame_type =
                FrameType::try_from(case["frameType"].as_u64().unwrap() as u8).unwrap();
            let actual = encode_frame(frame_type, &payload).unwrap();
            let expected = decode_base64(case["expectedFrameBase64"].as_str().unwrap());
            assert_eq!(actual, expected, "{}", case["id"]);

            let decoded = read_frame(&mut Cursor::new(actual), 4096).unwrap();
            assert_eq!(decoded.frame_type, frame_type);
            assert_eq!(decoded.payload, payload);
        }
    }

    #[test]
    fn command_recomputes_document_and_command_digests() {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        let case = vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "png-command")
            .unwrap();
        let admitted = parse_command(case["canonicalJson"].as_str().unwrap().as_bytes()).unwrap();
        assert_eq!(
            admitted.command.render_document_digest,
            case["renderDocumentDigest"].as_str().unwrap()
        );
        assert_eq!(
            admitted.command_digest,
            case["rendererCommandDigest"].as_str().unwrap()
        );
        assert_eq!(admitted.deadline_epoch_millis, 4_090_912_496_789);
    }

    #[test]
    fn strict_command_rejects_duplicate_unknown_noncanonical_and_digest_drift() {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        let command = vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "png-command")
            .unwrap()["canonicalJson"]
            .as_str()
            .unwrap();
        assert!(parse_command(format!(" {command}").as_bytes()).is_err());
        assert!(parse_command(command.replacen("{", "{\"unknown\":1,", 1).as_bytes()).is_err());
        assert!(
            parse_command(
                command
                    .replace(
                        "\"rendererProfile\":\"renderweave-renderer/1.0\"",
                        "\"rendererProfile\":\"_renderweave-renderer/1.0\"",
                    )
                    .as_bytes()
            )
            .is_err()
        );
        assert!(parse_command(
            command
                .replacen(
                    "\"contractVersion\":",
                    "\"contractVersion\":\"renderweave-render-command/1.0\",\"contractVersion\":",
                    1,
                )
                .as_bytes()
        )
        .is_err());
        assert!(
            parse_command(
                command
                    .replace(
                        "sha256:7c6308ebf6c6be199587f42265a586bd346f0e26f13e67c67503a7ce08395883",
                        "sha256:0c6308ebf6c6be199587f42265a586bd346f0e26f13e67c67503a7ce08395883",
                    )
                    .as_bytes()
            )
            .is_err()
        );
    }

    #[test]
    fn manifest_identity_and_hello_are_exact() {
        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        assert_eq!(
            identity.manifest_sha256,
            vectors["authorityContext"]["machineManifestSha256"]
                .as_str()
                .unwrap()
        );
        let server = vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "server-hello")
            .unwrap();
        assert_eq!(
            server_hello_bytes(&identity).unwrap(),
            server["canonicalJson"].as_str().unwrap().as_bytes()
        );
        let client = vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "client-hello")
            .unwrap();
        assert_eq!(
            client_hello_bytes(&identity.manifest_sha256).unwrap(),
            client["canonicalJson"].as_str().unwrap().as_bytes()
        );
        parse_client_hello(
            client["canonicalJson"].as_str().unwrap().as_bytes(),
            &identity.manifest_sha256,
        )
        .unwrap();
    }

    #[test]
    fn problem_matches_vector() {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        let problem = vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "problem")
            .unwrap();
        assert_eq!(
            problem_bytes(
                "123e4567-e89b-42d3-a456-426614174000",
                "RENDER_INTERNAL_ERROR",
                EngineStage::CommandAdmission,
            )
            .unwrap(),
            problem["canonicalJson"].as_str().unwrap().as_bytes()
        );
    }

    #[test]
    fn result_seal_matches_shared_png_metadata_and_image_payload_vectors() {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        let metadata = vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "png-result-metadata")
            .unwrap();
        let image_case = vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "png-result-image")
            .unwrap();
        let image = decode_base64(metadata["imageBase64"].as_str().unwrap());

        let sealed = seal_result(ResultSealInput {
            request_id: "123e4567-e89b-42d3-a456-426614174000",
            renderer_profile: "renderweave-renderer/1.0",
            dsl_version: "renderweave-render/1.0",
            layout_profile: "renderweave-layout/1.0",
            width_px: 1,
            height_px: 1,
            output: ResultOutputSelection::Png { dpi: 96 },
            image_bytes: image,
        })
        .unwrap();

        assert_eq!(
            sealed.metadata_payload(),
            metadata["canonicalJson"].as_str().unwrap().as_bytes()
        );
        assert_eq!(
            sealed.image_payload(),
            decode_base64(image_case["payloadBase64"].as_str().unwrap())
        );
        assert_eq!(sealed.byte_length(), 68);
        assert_eq!(
            sealed.content_sha256(),
            "431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460"
        );
        let debug = format!("{sealed:?}");
        assert!(!debug.contains("iVBOR"));
        assert!(!debug.contains("137, 80, 78, 71"));
    }

    #[test]
    fn result_seal_rejects_invalid_identity_dimensions_output_and_empty_bytes() {
        let png = |request_id, renderer_profile, width_px, height_px, dpi, image_bytes: &[u8]| {
            seal_result(ResultSealInput {
                request_id,
                renderer_profile,
                dsl_version: "renderweave-render/1.0",
                layout_profile: "renderweave-layout/1.0",
                width_px,
                height_px,
                output: ResultOutputSelection::Png { dpi },
                image_bytes: image_bytes.to_vec(),
            })
        };
        let bytes = [1_u8];
        assert!(png("not-a-uuid", "renderweave-renderer/1.0", 1, 1, 96, &bytes).is_err());
        assert!(
            png(
                "123e4567-e89b-42d3-a456-426614174000",
                "renderweave-renderer/2.0",
                1,
                1,
                96,
                &bytes
            )
            .is_err()
        );
        assert!(
            png(
                "123e4567-e89b-42d3-a456-426614174000",
                "renderweave-renderer/1.0",
                0,
                1,
                96,
                &bytes
            )
            .is_err()
        );
        assert!(
            png(
                "123e4567-e89b-42d3-a456-426614174000",
                "renderweave-renderer/1.0",
                1,
                0,
                96,
                &bytes
            )
            .is_err()
        );
        assert!(
            png(
                "123e4567-e89b-42d3-a456-426614174000",
                "renderweave-renderer/1.0",
                1,
                1,
                0,
                &bytes
            )
            .is_err()
        );
        assert!(
            png(
                "123e4567-e89b-42d3-a456-426614174000",
                "renderweave-renderer/1.0",
                1,
                1,
                96,
                &[]
            )
            .is_err()
        );
        assert!(
            seal_result(ResultSealInput {
                request_id: "123e4567-e89b-42d3-a456-426614174000",
                renderer_profile: "renderweave-renderer/1.0",
                dsl_version: "renderweave-render/1.0",
                layout_profile: "renderweave-layout/1.0",
                width_px: 1,
                height_px: 1,
                output: ResultOutputSelection::Jpeg {
                    dpi: 96,
                    quality: 0,
                },
                image_bytes: bytes.to_vec(),
            })
            .is_err()
        );
        assert!(
            seal_result(ResultSealInput {
                request_id: "123e4567-e89b-42d3-a456-426614174000",
                renderer_profile: "renderweave-renderer/1.0",
                dsl_version: "renderweave-render/2.0",
                layout_profile: "renderweave-layout/1.0",
                width_px: 1,
                height_px: 1,
                output: ResultOutputSelection::Png { dpi: 96 },
                image_bytes: bytes.to_vec(),
            })
            .is_err()
        );
        assert!(
            seal_result(ResultSealInput {
                request_id: "123e4567-e89b-42d3-a456-426614174000",
                renderer_profile: "renderweave-renderer/1.0",
                dsl_version: "renderweave-render/1.0",
                layout_profile: "renderweave-layout/2.0",
                width_px: 1,
                height_px: 1,
                output: ResultOutputSelection::Png { dpi: 96 },
                image_bytes: bytes.to_vec(),
            })
            .is_err()
        );
        for (dpi, quality) in [(0, 90), (96, 101)] {
            assert!(
                seal_result(ResultSealInput {
                    request_id: "123e4567-e89b-42d3-a456-426614174000",
                    renderer_profile: "renderweave-renderer/1.0",
                    dsl_version: "renderweave-render/1.0",
                    layout_profile: "renderweave-layout/1.0",
                    width_px: 1,
                    height_px: 1,
                    output: ResultOutputSelection::Jpeg { dpi, quality },
                    image_bytes: bytes.to_vec(),
                })
                .is_err()
            );
        }
    }

    #[test]
    fn result_seal_emits_closed_jpeg_shape_with_quality_last() {
        let image = [0xff_u8, 0xd8, 0xff, 0xd9];
        let digest = raw_sha256(&image);
        let sealed = seal_result(ResultSealInput {
            request_id: "123e4567-e89b-42d3-a456-426614174000",
            renderer_profile: "renderweave-renderer/1.0",
            dsl_version: "renderweave-render/1.0",
            layout_profile: "renderweave-layout/1.0",
            width_px: 7,
            height_px: 5,
            output: ResultOutputSelection::Jpeg {
                dpi: 300,
                quality: 90,
            },
            image_bytes: image.to_vec(),
        })
        .unwrap();

        assert_eq!(
            sealed.metadata_payload(),
            format!(
                "{{\"contractVersion\":\"renderweave-render-result/1.0\",\"requestId\":\"123e4567-e89b-42d3-a456-426614174000\",\"rendererProfile\":\"renderweave-renderer/1.0\",\"dslVersion\":\"renderweave-render/1.0\",\"layoutProfile\":\"renderweave-layout/1.0\",\"outputProfile\":\"renderweave-output-jpeg/1.0\",\"format\":\"JPEG\",\"mediaType\":\"image/jpeg\",\"widthPx\":7,\"heightPx\":5,\"dpi\":300,\"byteLength\":4,\"contentSha256\":\"{digest}\",\"quality\":90}}"
            )
            .as_bytes()
        );
        assert_eq!(&sealed.image_payload()[16..], image);
    }

    #[test]
    fn resource_problem_is_canonical_and_rejects_noncanonical_identity() {
        let resource_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        assert_eq!(
            resource_problem_bytes(
                "123e4567-e89b-42d3-a456-426614174000",
                "RESOURCE_LEASE_EXPIRED",
                EngineStage::CommandAdmission,
                resource_id,
            )
            .unwrap(),
            format!(
                "{{\"contractVersion\":\"renderweave-render-problem/1.0\",\"requestId\":\"123e4567-e89b-42d3-a456-426614174000\",\"code\":\"RESOURCE_LEASE_EXPIRED\",\"engineStage\":\"COMMAND_ADMISSION\",\"resourceId\":\"{resource_id}\",\"parameters\":{{}}}}"
            )
            .as_bytes()
        );
        assert!(
            resource_problem_bytes(
                "123e4567-e89b-42d3-a456-426614174000",
                "RESOURCE_LEASE_EXPIRED",
                EngineStage::CommandAdmission,
                "rwres_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            )
            .is_err()
        );
    }

    #[test]
    fn fetch_and_resource_capacity_problems_have_closed_canonical_shapes() {
        let request_id = "123e4567-e89b-42d3-a456-426614174000";
        let resource_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        for code in [
            "FETCH_FAILED",
            "LENGTH_MISMATCH",
            "HASH_MISMATCH",
            "MEDIA_MISMATCH",
            "DECODE_FAILED",
        ] {
            let bytes = resource_problem_bytes(
                request_id,
                code,
                EngineStage::ResourcePreparation,
                resource_id,
            )
            .unwrap();
            assert_eq!(
                bytes,
                format!(
                    "{{\"contractVersion\":\"renderweave-render-problem/1.0\",\"requestId\":\"{request_id}\",\"code\":\"{code}\",\"engineStage\":\"RESOURCE_PREPARATION\",\"resourceId\":\"{resource_id}\",\"parameters\":{{}}}}"
                )
                .as_bytes()
            );
        }
        assert_eq!(
            resource_limit_problem_bytes(
                request_id,
                "RESOURCE_BUDGET_EXCEEDED",
                EngineStage::ResourcePreparation,
                resource_id,
                "assetsAndFetch.physicalFetchBytesIncludingRetries",
            )
            .unwrap(),
            format!(
                "{{\"contractVersion\":\"renderweave-render-problem/1.0\",\"requestId\":\"{request_id}\",\"code\":\"RESOURCE_BUDGET_EXCEEDED\",\"engineStage\":\"RESOURCE_PREPARATION\",\"resourceId\":\"{resource_id}\",\"parameters\":{{\"limitId\":\"assetsAndFetch.physicalFetchBytesIncludingRetries\"}}}}"
            )
            .as_bytes()
        );
        assert!(
            resource_limit_problem_bytes(
                request_id,
                "FETCH_FAILED",
                EngineStage::ResourcePreparation,
                resource_id,
                "assetsAndFetch.physicalFetchBytesIncludingRetries",
            )
            .is_err()
        );
    }

    fn decode_base64(value: &str) -> Vec<u8> {
        let mut output = Vec::with_capacity(value.len() * 3 / 4);
        let mut block = [0_u8; 4];
        let mut count = 0;
        for byte in value.bytes() {
            block[count] = match byte {
                b'A'..=b'Z' => byte - b'A',
                b'a'..=b'z' => byte - b'a' + 26,
                b'0'..=b'9' => byte - b'0' + 52,
                b'+' => 62,
                b'/' => 63,
                b'=' => 64,
                _ => panic!("invalid fixture base64"),
            };
            count += 1;
            if count == 4 {
                output.push((block[0] << 2) | (block[1] >> 4));
                if block[2] != 64 {
                    output.push((block[1] << 4) | (block[2] >> 2));
                }
                if block[3] != 64 {
                    output.push((block[2] << 6) | block[3]);
                }
                count = 0;
            }
        }
        assert_eq!(count, 0);
        output
    }
}
