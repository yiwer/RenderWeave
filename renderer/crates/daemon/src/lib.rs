//! Constant Linux UDS daemon for the RenderWeave renderer process seam.
//!
//! The shipped default process intentionally has no registered raster profile and remains
//! fail-closed after resource preparation. The exact Linux `native-text-skia` candidate feature
//! routes its deliberately narrow Text subset through the same request registry into atomic PNG
//! or JPEG sealing for offline rehearsal; enabling those build features does not register
//! availability.

use renderweave_renderer_document::AdmittedRenderDocument;
#[cfg(any(unix, test))]
use renderweave_renderer_document::{validate_render_document, validate_resource_lease_coverage};
use renderweave_renderer_engine::{
    EngineCheckpoint, EngineExecutionControl, EnginePngError, render_jpeg_with_prepared_resources,
    render_jpeg_with_prepared_resources_controlled, render_png_with_prepared_resources,
    render_png_with_prepared_resources_controlled,
};
#[cfg(any(unix, test))]
use renderweave_renderer_engine::{
    EngineInterruption, EngineProblemStage, preflight_jpeg_output, preflight_png_output,
};
#[cfg(all(any(unix, test), not(feature = "native-text-skia")))]
use renderweave_renderer_layout::preflight_layout;
use renderweave_renderer_protocol::{
    AdmittedCommand, Frame, FrameType, OutputSelection, ProtocolError, ResultOutputSelection,
    ResultSealInput, SealedResult, seal_result,
};
#[cfg(any(unix, test))]
use renderweave_renderer_protocol::{
    EngineStage, ManifestIdentity, parse_cancel, parse_client_hello, parse_command, problem_bytes,
    read_frame, resource_limit_problem_bytes, resource_problem_bytes, server_hello_bytes,
    validate_process_manifest, write_frame,
};
#[cfg(any(unix, test))]
use renderweave_renderer_protocol::{limit_problem_bytes, occurrence_resource_problem_bytes};
#[cfg(target_os = "linux")]
use renderweave_renderer_resource::HttpsResourceFetcher;
use renderweave_renderer_resource::{
    ASSET_FETCH_PATH_PREFIX, FetchEgressPolicy, FetchTargetPolicy, PreparedResourceManifest,
};
#[cfg(test)]
use renderweave_renderer_resource::{FetchedResource, ResourceFetchProblem};
#[cfg(any(unix, test))]
use renderweave_renderer_resource::{
    ManifestResourcePreparer, ResourceFetcher, ResourcePipelineProblem, ResourcePreparationControl,
    ResourcePreparationInterruption, ResourcePreparationProfile,
};
#[cfg(any(unix, test))]
use std::collections::BTreeMap;
#[cfg(target_os = "linux")]
use std::collections::VecDeque;
use std::ffi::{OsStr, OsString};
use std::fmt::{Display, Formatter};
use std::io;
use std::path::{Path, PathBuf};
#[cfg(any(unix, test))]
use std::sync::Arc;
#[cfg(any(unix, test))]
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(target_os = "linux")]
use std::sync::{Mutex, mpsc};
#[cfg(target_os = "linux")]
use std::time::Duration;
#[cfg(any(unix, test))]
use std::time::{Instant, SystemTime, UNIX_EPOCH};

#[cfg(any(unix, test))]
const TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS: i64 = 300_000;
#[cfg(any(unix, test))]
const PRE_COMMAND_CANCEL_TOMBSTONE_MILLIS: i64 = 60_000;
#[cfg(target_os = "linux")]
const WAITING_QUEUE_CAPACITY: usize = 4;
#[cfg(target_os = "linux")]
const WAITING_QUEUE_MILLIS: i64 = 5 * 1_000;

#[derive(Debug)]
pub enum DaemonError {
    Configuration(&'static str),
    Io(io::Error),
    Protocol(ProtocolError),
}

impl Display for DaemonError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Configuration(message) => formatter.write_str(message),
            Self::Io(error) => write!(formatter, "renderer daemon I/O failed: {error}"),
            Self::Protocol(error) => write!(formatter, "renderer daemon protocol failed: {error}"),
        }
    }
}

impl std::error::Error for DaemonError {}

impl From<io::Error> for DaemonError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<ProtocolError> for DaemonError {
    fn from(value: ProtocolError) -> Self {
        Self::Protocol(value)
    }
}

impl DaemonError {
    pub fn exit_code(&self) -> i32 {
        match self {
            Self::Configuration(_) => 64,
            Self::Io(_) | Self::Protocol(_) => 70,
        }
    }
}

#[derive(Debug)]
pub enum PreparedPngResultError {
    Contract(&'static str),
    Engine(EnginePngError),
    Seal(ProtocolError),
}

impl Display for PreparedPngResultError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Contract(message) => formatter.write_str(message),
            Self::Engine(error) => write!(formatter, "prepared PNG execution failed: {error}"),
            Self::Seal(error) => write!(formatter, "prepared PNG result seal failed: {error}"),
        }
    }
}

impl std::error::Error for PreparedPngResultError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Contract(_) => None,
            Self::Engine(error) => Some(error),
            Self::Seal(error) => Some(error),
        }
    }
}

impl From<EnginePngError> for PreparedPngResultError {
    fn from(error: EnginePngError) -> Self {
        Self::Engine(error)
    }
}

impl From<ProtocolError> for PreparedPngResultError {
    fn from(error: ProtocolError) -> Self {
        Self::Seal(error)
    }
}

/// Composes already-admitted and fully prepared inputs into one immutable PNG result.
///
/// This function does not establish Profile availability. The default daemon build never invokes
/// it; the exact candidate feature invokes it only after command, document, lease, target, layout,
/// and resource preparation admission have succeeded.
pub fn seal_prepared_png_result(
    command: &AdmittedCommand,
    document: &AdmittedRenderDocument,
    prepared_resources: &PreparedResourceManifest,
) -> Result<SealedResult, PreparedPngResultError> {
    seal_prepared_png_result_internal(command, document, prepared_resources, None)
}

pub fn seal_prepared_png_result_controlled(
    command: &AdmittedCommand,
    document: &AdmittedRenderDocument,
    prepared_resources: &PreparedResourceManifest,
    control: &dyn EngineExecutionControl,
) -> Result<SealedResult, PreparedPngResultError> {
    seal_prepared_png_result_internal(command, document, prepared_resources, Some(control))
}

fn seal_prepared_png_result_internal(
    command: &AdmittedCommand,
    document: &AdmittedRenderDocument,
    prepared_resources: &PreparedResourceManifest,
    control: Option<&dyn EngineExecutionControl>,
) -> Result<SealedResult, PreparedPngResultError> {
    validate_prepared_identity(command, document)?;
    let dpi = match &command.command.output {
        OutputSelection::Png(output) => output.dpi,
        OutputSelection::Jpeg(_) => {
            return Err(PreparedPngResultError::Contract(
                "prepared PNG result kernel does not support JPEG",
            ));
        }
    };

    let output = match control {
        Some(control) => render_png_with_prepared_resources_controlled(
            document,
            prepared_resources,
            dpi,
            control,
        )?,
        None => render_png_with_prepared_resources(document, prepared_resources, dpi)?,
    };
    if output.dpi() != dpi
        || output.output_profile() != "renderweave-output-png/1.0"
        || output.media_type() != "image/png"
    {
        return Err(PreparedPngResultError::Contract(
            "prepared PNG Engine output identity diverged",
        ));
    }
    let byte_length = output.byte_length();
    let content_sha256 = output.content_sha256().to_owned();
    seal_encoded_result(
        command,
        output.width_px(),
        output.height_px(),
        ResultOutputSelection::Png { dpi },
        byte_length,
        &content_sha256,
        output.into_bytes(),
        control,
    )
}

pub fn seal_prepared_jpeg_result(
    command: &AdmittedCommand,
    document: &AdmittedRenderDocument,
    prepared_resources: &PreparedResourceManifest,
) -> Result<SealedResult, PreparedPngResultError> {
    seal_prepared_jpeg_result_internal(command, document, prepared_resources, None)
}

pub fn seal_prepared_jpeg_result_controlled(
    command: &AdmittedCommand,
    document: &AdmittedRenderDocument,
    prepared_resources: &PreparedResourceManifest,
    control: &dyn EngineExecutionControl,
) -> Result<SealedResult, PreparedPngResultError> {
    seal_prepared_jpeg_result_internal(command, document, prepared_resources, Some(control))
}

fn seal_prepared_jpeg_result_internal(
    command: &AdmittedCommand,
    document: &AdmittedRenderDocument,
    prepared_resources: &PreparedResourceManifest,
    control: Option<&dyn EngineExecutionControl>,
) -> Result<SealedResult, PreparedPngResultError> {
    validate_prepared_identity(command, document)?;
    let (dpi, quality) = match &command.command.output {
        OutputSelection::Jpeg(output) => (output.dpi, output.quality),
        OutputSelection::Png(_) => {
            return Err(PreparedPngResultError::Contract(
                "prepared JPEG result kernel requires JPEG",
            ));
        }
    };
    let output = match control {
        Some(control) => render_jpeg_with_prepared_resources_controlled(
            document,
            prepared_resources,
            dpi,
            quality,
            control,
        )?,
        None => render_jpeg_with_prepared_resources(document, prepared_resources, dpi, quality)?,
    };
    if output.dpi() != dpi
        || output.quality() != quality
        || output.output_profile() != "renderweave-output-jpeg/1.0"
        || output.media_type() != "image/jpeg"
    {
        return Err(PreparedPngResultError::Contract(
            "prepared JPEG Engine output identity diverged",
        ));
    }
    let byte_length = output.byte_length();
    let content_sha256 = output.content_sha256().to_owned();
    seal_encoded_result(
        command,
        output.width_px(),
        output.height_px(),
        ResultOutputSelection::Jpeg { dpi, quality },
        byte_length,
        &content_sha256,
        output.into_bytes(),
        control,
    )
}

fn validate_prepared_identity(
    command: &AdmittedCommand,
    document: &AdmittedRenderDocument,
) -> Result<(), PreparedPngResultError> {
    if command.command.renderer_profile != "renderweave-renderer/1.0" {
        return Err(PreparedPngResultError::Contract(
            "prepared command renderer Profile is not exact",
        ));
    }
    if command.command.document.get().as_bytes() != document.canonical_document().as_bytes() {
        return Err(PreparedPngResultError::Contract(
            "prepared command and admitted document identities diverged",
        ));
    }
    if command.command.diagnostics.layout_trace {
        return Err(PreparedPngResultError::Contract(
            "prepared result kernel does not support layout trace",
        ));
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn seal_encoded_result(
    command: &AdmittedCommand,
    width_px: u32,
    height_px: u32,
    output_selection: ResultOutputSelection,
    byte_length: usize,
    content_sha256: &str,
    image_bytes: Vec<u8>,
    control: Option<&dyn EngineExecutionControl>,
) -> Result<SealedResult, PreparedPngResultError> {
    let byte_length = u64::try_from(byte_length).map_err(|_| {
        PreparedPngResultError::Contract("prepared Engine byte length exceeds uint64")
    })?;
    let content_sha256 = content_sha256.to_owned();
    if let Some(control) = control {
        prepared_checkpoint(control, EngineCheckpoint::OutputSeal)?;
    }
    let sealed = seal_result(ResultSealInput {
        request_id: &command.command.request_id,
        renderer_profile: &command.command.renderer_profile,
        dsl_version: "renderweave-render/1.0",
        layout_profile: "renderweave-layout/1.0",
        width_px,
        height_px,
        output: output_selection,
        image_bytes,
    })?;
    if let Some(control) = control {
        prepared_checkpoint(control, EngineCheckpoint::OutputSeal)?;
    }
    if sealed.byte_length() != byte_length
        || format!("sha256:{}", sealed.content_sha256()) != content_sha256
    {
        return Err(PreparedPngResultError::Contract(
            "prepared Engine and sealed result identities diverged",
        ));
    }
    Ok(sealed)
}

fn prepared_checkpoint(
    control: &dyn EngineExecutionControl,
    checkpoint: EngineCheckpoint,
) -> Result<(), PreparedPngResultError> {
    control.checkpoint(checkpoint).map_err(|interruption| {
        PreparedPngResultError::Engine(EnginePngError::Interrupted {
            interruption,
            checkpoint,
        })
    })
}

#[derive(Clone, Debug)]
pub struct DaemonConfiguration {
    socket_path: PathBuf,
    manifest_path: PathBuf,
    fetch_target_policy: FetchTargetPolicy,
    fetch_egress_policy: FetchEgressPolicy,
    maximum_framed_bytes: usize,
}

impl DaemonConfiguration {
    pub fn new<I, S>(
        socket_path: impl Into<PathBuf>,
        manifest_path: impl Into<PathBuf>,
        asset_fetch_origin: &str,
        asset_fetch_allowed_ips: I,
        maximum_framed_bytes: usize,
    ) -> Result<Self, DaemonError>
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        let socket_path = socket_path.into();
        let manifest_path = manifest_path.into();
        if socket_path.as_os_str().is_empty() {
            return Err(DaemonError::Configuration(
                "renderer socket path is required",
            ));
        }
        if manifest_path.as_os_str().is_empty() {
            return Err(DaemonError::Configuration(
                "renderer manifest path is required",
            ));
        }
        if maximum_framed_bytes == 0 {
            return Err(DaemonError::Configuration(
                "renderer maximum frame bytes must be positive",
            ));
        }
        let fetch_target_policy =
            FetchTargetPolicy::new(asset_fetch_origin, ASSET_FETCH_PATH_PREFIX).map_err(|_| {
                DaemonError::Configuration(
                    "renderer asset fetch origin must be a canonical HTTPS origin",
                )
            })?;
        let fetch_egress_policy =
            FetchEgressPolicy::new(asset_fetch_allowed_ips).map_err(|_| {
                DaemonError::Configuration(
                    "renderer asset fetch allowed IPs must be 1..16 unique canonical addresses",
                )
            })?;
        Ok(Self {
            socket_path,
            manifest_path,
            fetch_target_policy,
            fetch_egress_policy,
            maximum_framed_bytes,
        })
    }

    pub fn socket_path(&self) -> &Path {
        &self.socket_path
    }

    pub fn manifest_path(&self) -> &Path {
        &self.manifest_path
    }

    pub fn maximum_framed_bytes(&self) -> usize {
        self.maximum_framed_bytes
    }

    pub fn fetch_target_policy(&self) -> &FetchTargetPolicy {
        &self.fetch_target_policy
    }

    pub fn fetch_egress_policy(&self) -> &FetchEgressPolicy {
        &self.fetch_egress_policy
    }
}

pub fn run_from_arguments(
    arguments: impl IntoIterator<Item = OsString>,
) -> Result<(), DaemonError> {
    let mut arguments = arguments.into_iter();
    let _program = arguments.next();
    let mut socket_path = None;
    let mut manifest_path = None;
    let mut asset_fetch_origin = None;
    let mut asset_fetch_allowed_ips = Vec::new();
    let mut maximum_framed_bytes = None;
    while let Some(argument) = arguments.next() {
        if argument == OsStr::new("--socket") {
            set_once(
                &mut socket_path,
                arguments.next(),
                "--socket must appear exactly once with a value",
            )?;
        } else if argument == OsStr::new("--manifest") {
            set_once(
                &mut manifest_path,
                arguments.next(),
                "--manifest must appear exactly once with a value",
            )?;
        } else if argument == OsStr::new("--asset-fetch-origin") {
            set_once(
                &mut asset_fetch_origin,
                arguments.next(),
                "--asset-fetch-origin must appear exactly once with a value",
            )?;
        } else if argument == OsStr::new("--asset-fetch-allowed-ip") {
            asset_fetch_allowed_ips.push(arguments.next().ok_or(DaemonError::Configuration(
                "--asset-fetch-allowed-ip requires a value",
            ))?);
        } else if argument == OsStr::new("--max-frame-bytes") {
            if maximum_framed_bytes.is_some() {
                return Err(DaemonError::Configuration(
                    "--max-frame-bytes must appear exactly once",
                ));
            }
            let raw = arguments.next().ok_or(DaemonError::Configuration(
                "--max-frame-bytes requires a value",
            ))?;
            let raw = raw.to_str().ok_or(DaemonError::Configuration(
                "--max-frame-bytes must be UTF-8 decimal",
            ))?;
            maximum_framed_bytes = Some(raw.parse::<usize>().map_err(|_| {
                DaemonError::Configuration("--max-frame-bytes must be positive decimal")
            })?);
        } else {
            return Err(DaemonError::Configuration(
                "unknown renderer daemon argument",
            ));
        }
    }
    let asset_fetch_origin = asset_fetch_origin.ok_or(DaemonError::Configuration(
        "--asset-fetch-origin is required",
    ))?;
    let asset_fetch_origin = asset_fetch_origin
        .to_str()
        .ok_or(DaemonError::Configuration(
            "--asset-fetch-origin must be canonical UTF-8",
        ))?;
    let asset_fetch_allowed_ips = asset_fetch_allowed_ips
        .iter()
        .map(|value| {
            value.to_str().ok_or(DaemonError::Configuration(
                "--asset-fetch-allowed-ip must be canonical UTF-8",
            ))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let configuration = DaemonConfiguration::new(
        socket_path.ok_or(DaemonError::Configuration("--socket is required"))?,
        manifest_path.ok_or(DaemonError::Configuration("--manifest is required"))?,
        asset_fetch_origin,
        asset_fetch_allowed_ips,
        maximum_framed_bytes.ok_or(DaemonError::Configuration("--max-frame-bytes is required"))?,
    )?;
    run(configuration)
}

fn set_once(
    destination: &mut Option<OsString>,
    value: Option<OsString>,
    message: &'static str,
) -> Result<(), DaemonError> {
    if destination.is_some() {
        return Err(DaemonError::Configuration(message));
    }
    *destination = Some(value.ok_or(DaemonError::Configuration(message))?);
    Ok(())
}

#[cfg(target_os = "linux")]
pub fn run(configuration: DaemonConfiguration) -> Result<(), DaemonError> {
    use std::os::unix::fs::PermissionsExt;
    use std::os::unix::net::UnixListener;

    let manifest_bytes = std::fs::read(configuration.manifest_path())?;
    let identity = validate_process_manifest(&manifest_bytes)?;
    if configuration.socket_path().try_exists()? {
        return Err(DaemonError::Configuration(
            "renderer socket path must not already exist",
        ));
    }
    let listener = UnixListener::bind(configuration.socket_path())?;
    std::fs::set_permissions(
        configuration.socket_path(),
        std::fs::Permissions::from_mode(0o600),
    )?;
    let resource_fetcher = Arc::new(HttpsResourceFetcher::new(
        configuration.fetch_egress_policy().clone(),
    ));
    let registry = ConcurrentRequestRegistry::new(
        configuration.fetch_target_policy().clone(),
        resource_fetcher,
    );
    eprintln!("renderer daemon ready");
    for accepted in listener.incoming() {
        let connection = accepted?;
        if serve_unix_connection(
            connection,
            &identity,
            &registry,
            configuration.maximum_framed_bytes(),
        )
        .is_err()
        {
            // No payload, URL, token, document, image, request identity, or raw cause is logged.
            eprintln!("renderer connection rejected");
        }
    }
    Ok(())
}

#[cfg(not(target_os = "linux"))]
pub fn run(_configuration: DaemonConfiguration) -> Result<(), DaemonError> {
    Err(DaemonError::Configuration(
        "renderer daemon production transport requires Linux UDS",
    ))
}

#[cfg(test)]
fn serve_connection<S: io::Read + io::Write>(
    connection: &mut S,
    identity: &ManifestIdentity,
    registry: &mut RequestRegistry,
    maximum_framed_bytes: usize,
) -> Result<(), DaemonError> {
    let hello = read_frame(connection, maximum_framed_bytes)?;
    if hello.frame_type != FrameType::ClientHello {
        return Err(DaemonError::Protocol(ProtocolError::Invalid(
            "first renderer frame must be CLIENT_HELLO",
        )));
    }
    parse_client_hello(&hello.payload, &identity.manifest_sha256)?;
    write_frame(
        connection,
        FrameType::ServerHello,
        &server_hello_bytes(identity)?,
    )?;

    loop {
        let frame = match read_frame(connection, maximum_framed_bytes) {
            Ok(frame) => frame,
            Err(ProtocolError::Io(error)) if error.kind() == io::ErrorKind::UnexpectedEof => {
                return Ok(());
            }
            Err(error) => return Err(error.into()),
        };
        let mut clock = now_epoch_millis;
        let response = registry.handle_with_clock(frame, &mut clock)?;
        response.write_to(connection)?;
    }
}

#[cfg(target_os = "linux")]
fn serve_unix_connection(
    mut connection: std::os::unix::net::UnixStream,
    identity: &ManifestIdentity,
    registry: &ConcurrentRequestRegistry,
    maximum_framed_bytes: usize,
) -> Result<(), DaemonError> {
    let hello = read_frame(&mut connection, maximum_framed_bytes)?;
    if hello.frame_type != FrameType::ClientHello {
        return Err(DaemonError::Protocol(ProtocolError::Invalid(
            "first renderer frame must be CLIENT_HELLO",
        )));
    }
    parse_client_hello(&hello.payload, &identity.manifest_sha256)?;
    write_frame(
        &mut connection,
        FrameType::ServerHello,
        &server_hello_bytes(identity)?,
    )?;

    let mut writer = connection.try_clone()?;
    let (response_sender, response_receiver) = mpsc::channel::<TerminalResponse>();
    let writer_thread = std::thread::spawn(move || -> Result<(), ProtocolError> {
        for response in response_receiver {
            response.write_to(&mut writer)?;
        }
        Ok(())
    });

    let reader_result = loop {
        let frame = match read_frame(&mut connection, maximum_framed_bytes) {
            Ok(frame) => frame,
            Err(ProtocolError::Io(error)) if error.kind() == io::ErrorKind::UnexpectedEof => {
                break Ok(());
            }
            Err(error) => break Err(DaemonError::Protocol(error)),
        };
        if let Err(error) = registry.handle(frame, now_epoch_millis(), &response_sender) {
            break Err(DaemonError::Protocol(error));
        }
    };
    drop(response_sender);
    let writer_result = writer_thread
        .join()
        .map_err(|_| DaemonError::Io(io::Error::other("renderer writer thread panicked")))?;
    reader_result?;
    writer_result?;
    Ok(())
}

#[derive(Clone, Debug)]
pub struct TerminalResponse {
    frames: Vec<Frame>,
}

impl TerminalResponse {
    #[cfg(any(unix, test))]
    fn problem(payload: Vec<u8>) -> Self {
        Self {
            frames: vec![Frame {
                frame_type: FrameType::Problem,
                payload,
            }],
        }
    }

    pub fn sealed_result(result: SealedResult) -> Self {
        let (metadata_payload, image_payload) = result.into_payloads();
        Self {
            frames: vec![
                Frame {
                    frame_type: FrameType::ResultMetadata,
                    payload: metadata_payload,
                },
                Frame {
                    frame_type: FrameType::ResultImage,
                    payload: image_payload,
                },
            ],
        }
    }

    pub fn frames(&self) -> &[Frame] {
        &self.frames
    }

    #[cfg(any(unix, test))]
    fn write_to(&self, writer: &mut impl io::Write) -> Result<(), ProtocolError> {
        for frame in &self.frames {
            write_frame(writer, frame.frame_type, &frame.payload)?;
        }
        Ok(())
    }
}

#[cfg(any(unix, test))]
#[derive(Clone, Debug)]
struct RegistryEntry {
    command_digest: String,
    deadline_epoch_millis: i64,
    retain_until_epoch_millis: i64,
    terminal_response: TerminalResponse,
}

#[cfg(test)]
struct RequestRegistry {
    entries: BTreeMap<String, RegistryEntry>,
    executor: RequestExecutor,
}

#[cfg(test)]
impl RequestRegistry {
    fn new(
        fetch_target_policy: FetchTargetPolicy,
        resource_fetcher: Arc<dyn ResourceFetcher>,
    ) -> Self {
        Self {
            entries: BTreeMap::new(),
            executor: RequestExecutor {
                fetch_target_policy,
                resource_fetcher,
            },
        }
    }

    #[cfg(test)]
    fn handle(
        &mut self,
        frame: Frame,
        now_epoch_millis: i64,
    ) -> Result<TerminalResponse, ProtocolError> {
        self.handle_with_clock(frame, &mut || now_epoch_millis)
    }

    fn handle_with_clock<F>(
        &mut self,
        frame: Frame,
        clock: &mut F,
    ) -> Result<TerminalResponse, ProtocolError>
    where
        F: FnMut() -> i64,
    {
        let now_epoch_millis = clock();
        self.entries
            .retain(|_, entry| entry.retain_until_epoch_millis > now_epoch_millis);
        match frame.frame_type {
            FrameType::Command => self.handle_command(&frame.payload, now_epoch_millis, clock),
            FrameType::Cancel => self.handle_cancel(&frame.payload, now_epoch_millis),
            _ => Err(ProtocolError::Invalid(
                "post-handshake client frame must be COMMAND or CANCEL",
            )),
        }
    }

    fn handle_command<F>(
        &mut self,
        payload: &[u8],
        now_epoch_millis: i64,
        clock: &mut F,
    ) -> Result<TerminalResponse, ProtocolError>
    where
        F: FnMut() -> i64,
    {
        let admitted = parse_command(payload)?;
        let request_id = admitted.command.request_id.clone();
        if let Some(existing) = self.entries.get(&request_id) {
            if existing.command_digest == admitted.command_digest
                && existing.deadline_epoch_millis == admitted.deadline_epoch_millis
            {
                return Ok(existing.terminal_response.clone());
            }
            return Ok(TerminalResponse::problem(problem_bytes(
                &request_id,
                "RENDER_REQUEST_CONFLICT",
                EngineStage::RequestControl,
            )?));
        }

        let control = RequestExecutionControl::fixed(
            admitted.deadline_epoch_millis,
            now_epoch_millis,
            Arc::new(AtomicBool::new(false)),
        );
        let terminal_response = self
            .executor
            .execute(&admitted, now_epoch_millis, &control)?;
        let sealed_at_epoch_millis = clock().max(now_epoch_millis);
        let terminal_response = if admitted.deadline_epoch_millis <= sealed_at_epoch_millis {
            TerminalResponse::problem(problem_bytes(
                &request_id,
                "RENDER_DEADLINE_EXCEEDED",
                EngineStage::OutputSeal,
            )?)
        } else {
            terminal_response
        };
        let retain_until_epoch_millis = admitted
            .deadline_epoch_millis
            .max(sealed_at_epoch_millis)
            .checked_add(TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS)
            .ok_or(ProtocolError::Invalid(
                "terminal registry retention deadline overflow",
            ))?;
        self.entries.insert(
            request_id,
            RegistryEntry {
                command_digest: admitted.command_digest,
                deadline_epoch_millis: admitted.deadline_epoch_millis,
                retain_until_epoch_millis,
                terminal_response: terminal_response.clone(),
            },
        );
        Ok(terminal_response)
    }

    fn handle_cancel(
        &mut self,
        payload: &[u8],
        now_epoch_millis: i64,
    ) -> Result<TerminalResponse, ProtocolError> {
        let cancel = parse_cancel(payload)?;
        if let Some(existing) = self.entries.get(&cancel.cancel.request_id) {
            if existing.command_digest == cancel.cancel.renderer_command_digest
                && existing.deadline_epoch_millis == cancel.deadline_epoch_millis
            {
                return Ok(existing.terminal_response.clone());
            }
            return Ok(TerminalResponse::problem(problem_bytes(
                &cancel.cancel.request_id,
                "RENDER_REQUEST_CONFLICT",
                EngineStage::RequestControl,
            )?));
        }
        let deadline_epoch_millis = cancel.deadline_epoch_millis;
        let code = if deadline_epoch_millis <= now_epoch_millis {
            "RENDER_DEADLINE_EXCEEDED"
        } else {
            "RENDER_CANCELLED"
        };
        let problem_payload =
            problem_bytes(&cancel.cancel.request_id, code, EngineStage::RequestControl)?;
        let terminal_response = TerminalResponse::problem(problem_payload);
        let retain_until_epoch_millis = now_epoch_millis
            .checked_add(PRE_COMMAND_CANCEL_TOMBSTONE_MILLIS)
            .ok_or(ProtocolError::Invalid(
                "pre-command cancel tombstone retention deadline overflow",
            ))?;
        self.entries.insert(
            cancel.cancel.request_id,
            RegistryEntry {
                command_digest: cancel.cancel.renderer_command_digest,
                deadline_epoch_millis,
                retain_until_epoch_millis,
                terminal_response: terminal_response.clone(),
            },
        );
        Ok(terminal_response)
    }
}

#[cfg(any(unix, test))]
#[derive(Clone)]
struct RequestExecutionControl {
    deadline_epoch_millis: i64,
    fixed_now_epoch_millis: Option<i64>,
    monotonic_deadline: Option<Instant>,
    cancellation: Arc<AtomicBool>,
}

#[cfg(any(unix, test))]
impl RequestExecutionControl {
    #[cfg(test)]
    fn fixed(
        deadline_epoch_millis: i64,
        now_epoch_millis: i64,
        cancellation: Arc<AtomicBool>,
    ) -> Self {
        Self {
            deadline_epoch_millis,
            fixed_now_epoch_millis: Some(now_epoch_millis),
            monotonic_deadline: None,
            cancellation,
        }
    }

    #[cfg(target_os = "linux")]
    fn live(deadline_epoch_millis: i64, cancellation: Arc<AtomicBool>) -> Self {
        let converted_at = Instant::now();
        let remaining_millis = deadline_epoch_millis.saturating_sub(crate::now_epoch_millis());
        let monotonic_deadline = u64::try_from(remaining_millis)
            .ok()
            .and_then(|remaining| converted_at.checked_add(Duration::from_millis(remaining)))
            .unwrap_or(converted_at);
        Self {
            deadline_epoch_millis,
            fixed_now_epoch_millis: None,
            monotonic_deadline: Some(monotonic_deadline),
            cancellation,
        }
    }

    fn interruption(&self) -> Option<EngineInterruption> {
        if self.cancellation.load(Ordering::SeqCst) {
            return Some(EngineInterruption::Cancelled);
        }
        let expired = match (self.fixed_now_epoch_millis, self.monotonic_deadline) {
            (Some(now_epoch_millis), None) => self.deadline_epoch_millis <= now_epoch_millis,
            (None, Some(monotonic_deadline)) => Instant::now() >= monotonic_deadline,
            _ => true,
        };
        expired.then_some(EngineInterruption::DeadlineExceeded)
    }
}

#[cfg(any(unix, test))]
impl EngineExecutionControl for RequestExecutionControl {
    fn checkpoint(&self, _checkpoint: EngineCheckpoint) -> Result<(), EngineInterruption> {
        match self.interruption() {
            Some(interruption) => Err(interruption),
            None => Ok(()),
        }
    }
}

#[cfg(any(unix, test))]
impl ResourcePreparationControl for RequestExecutionControl {
    fn checkpoint(&self) -> Result<(), ResourcePreparationInterruption> {
        match self.interruption() {
            Some(EngineInterruption::Cancelled) => Err(ResourcePreparationInterruption::Cancelled),
            Some(EngineInterruption::DeadlineExceeded) => {
                Err(ResourcePreparationInterruption::DeadlineExceeded)
            }
            None => Ok(()),
        }
    }
}

#[cfg(any(unix, test))]
#[derive(Clone)]
struct RequestExecutor {
    fetch_target_policy: FetchTargetPolicy,
    resource_fetcher: Arc<dyn ResourceFetcher>,
}

#[cfg(any(unix, test))]
impl RequestExecutor {
    fn execute(
        &self,
        admitted: &AdmittedCommand,
        now_epoch_millis: i64,
        control: &RequestExecutionControl,
    ) -> Result<TerminalResponse, ProtocolError> {
        let request_id = &admitted.command.request_id;
        if admitted.deadline_epoch_millis <= now_epoch_millis {
            return terminal_problem(problem_bytes(
                request_id,
                "RENDER_DEADLINE_EXCEEDED",
                EngineStage::RequestControl,
            ));
        }
        let document = match validate_render_document(admitted.command.document.get()) {
            Ok(document) => document,
            Err(_) => {
                return terminal_problem(problem_bytes(
                    request_id,
                    "RENDER_INTERNAL_ERROR",
                    EngineStage::DocumentAdmission,
                ));
            }
        };
        if let Err(violation) =
            validate_resource_lease_coverage(&document, admitted.deadline_epoch_millis)
        {
            return terminal_problem(resource_problem_bytes(
                request_id,
                "RESOURCE_LEASE_EXPIRED",
                EngineStage::CommandAdmission,
                violation.resource_id(),
            ));
        }
        let output_preflight = match &admitted.command.output {
            OutputSelection::Png(output) => preflight_png_output(&document, output.dpi),
            OutputSelection::Jpeg(output) => {
                preflight_jpeg_output(&document, output.dpi, output.quality)
            }
        };
        if let Err(error) = output_preflight {
            return terminal_problem(engine_png_problem_bytes(request_id, &error));
        }

        let preparer = ManifestResourcePreparer::new(
            &self.fetch_target_policy,
            self.resource_fetcher.as_ref(),
            ResourcePreparationProfile::RendererV1,
        );
        let prepared_manifest = match preparer.prepare_controlled(
            document.resources(),
            admitted.deadline_epoch_millis,
            now_epoch_millis,
            control,
        ) {
            Ok(prepared_manifest) => prepared_manifest,
            Err(problem) => {
                return terminal_problem(resource_pipeline_problem_bytes(request_id, &problem));
            }
        };
        #[cfg(not(feature = "native-text-skia"))]
        if preflight_layout(&document).is_err() {
            return terminal_problem(problem_bytes(
                request_id,
                "RENDER_INTERNAL_ERROR",
                EngineStage::DocumentAdmission,
            ));
        }

        #[cfg(feature = "native-text-skia")]
        {
            let result = match admitted.command.output {
                OutputSelection::Png(_) => seal_prepared_png_result_controlled(
                    admitted,
                    &document,
                    &prepared_manifest,
                    control,
                ),
                OutputSelection::Jpeg(_) => seal_prepared_jpeg_result_controlled(
                    admitted,
                    &document,
                    &prepared_manifest,
                    control,
                ),
            };
            match result {
                Ok(result) => Ok(TerminalResponse::sealed_result(result)),
                Err(error) => terminal_problem(prepared_png_problem_bytes(request_id, &error)),
            }
        }
        #[cfg(not(feature = "native-text-skia"))]
        {
            let _ = prepared_manifest;
            // The shipped default build still has no registered raster profile. Only the exact
            // T214 candidate feature executes the new path; availability remains fail-closed.
            terminal_problem(problem_bytes(
                request_id,
                "RENDER_INTERNAL_ERROR",
                EngineStage::CommandAdmission,
            ))
        }
    }
}

#[cfg(any(unix, test))]
fn terminal_problem(
    payload: Result<Vec<u8>, ProtocolError>,
) -> Result<TerminalResponse, ProtocolError> {
    Ok(TerminalResponse::problem(payload?))
}

#[cfg(target_os = "linux")]
struct ActiveRequest {
    request_id: String,
    command_digest: String,
    deadline_epoch_millis: i64,
    cancellation: Arc<AtomicBool>,
}

#[cfg(target_os = "linux")]
struct QueuedRequest {
    admitted: AdmittedCommand,
    accepted_at_epoch_millis: i64,
    response_sender: mpsc::Sender<TerminalResponse>,
}

#[cfg(target_os = "linux")]
struct BusyReservation {
    command_digest: String,
    deadline_epoch_millis: i64,
}

#[cfg(target_os = "linux")]
#[derive(Default)]
struct ConcurrentRegistryState {
    entries: BTreeMap<String, RegistryEntry>,
    active: Option<ActiveRequest>,
    waiting: VecDeque<QueuedRequest>,
    busy_reservations: BTreeMap<String, BusyReservation>,
}

#[cfg(target_os = "linux")]
#[derive(Clone)]
struct ConcurrentRequestRegistry {
    state: Arc<Mutex<ConcurrentRegistryState>>,
    executor: RequestExecutor,
}

#[cfg(target_os = "linux")]
impl ConcurrentRequestRegistry {
    fn new(
        fetch_target_policy: FetchTargetPolicy,
        resource_fetcher: Arc<dyn ResourceFetcher>,
    ) -> Self {
        Self {
            state: Arc::new(Mutex::new(ConcurrentRegistryState::default())),
            executor: RequestExecutor {
                fetch_target_policy,
                resource_fetcher,
            },
        }
    }

    fn handle(
        &self,
        frame: Frame,
        now_epoch_millis: i64,
        response_sender: &mpsc::Sender<TerminalResponse>,
    ) -> Result<(), ProtocolError> {
        match frame.frame_type {
            FrameType::Command => {
                self.handle_command(&frame.payload, now_epoch_millis, response_sender)
            }
            FrameType::Cancel => {
                self.handle_cancel(&frame.payload, now_epoch_millis, response_sender)
            }
            _ => Err(ProtocolError::Invalid(
                "post-handshake client frame must be COMMAND or CANCEL",
            )),
        }
    }

    fn handle_command(
        &self,
        payload: &[u8],
        now_epoch_millis: i64,
        response_sender: &mpsc::Sender<TerminalResponse>,
    ) -> Result<(), ProtocolError> {
        let admitted = parse_command(payload)?;
        let request_id = admitted.command.request_id.clone();
        let mut state = self.state.lock().expect("request registry mutex poisoned");
        state
            .entries
            .retain(|_, entry| entry.retain_until_epoch_millis > now_epoch_millis);
        state
            .busy_reservations
            .retain(|_, reservation| reservation.deadline_epoch_millis > now_epoch_millis);
        if let Some(existing) = state.entries.get(&request_id) {
            let response = if existing.command_digest == admitted.command_digest
                && existing.deadline_epoch_millis == admitted.deadline_epoch_millis
            {
                existing.terminal_response.clone()
            } else {
                TerminalResponse::problem(problem_bytes(
                    &request_id,
                    "RENDER_REQUEST_CONFLICT",
                    EngineStage::RequestControl,
                )?)
            };
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }
        if let Some(active) = state.active.as_ref()
            && active.request_id == request_id
        {
            if active.command_digest == admitted.command_digest
                && active.deadline_epoch_millis == admitted.deadline_epoch_millis
            {
                return Ok(());
            }
            let response = TerminalResponse::problem(problem_bytes(
                &request_id,
                "RENDER_REQUEST_CONFLICT",
                EngineStage::RequestControl,
            )?);
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }
        if let Some(waiting) = state
            .waiting
            .iter()
            .find(|waiting| waiting.admitted.command.request_id == request_id)
        {
            if waiting.admitted.command_digest == admitted.command_digest
                && waiting.admitted.deadline_epoch_millis == admitted.deadline_epoch_millis
            {
                return Ok(());
            }
            let response = TerminalResponse::problem(problem_bytes(
                &request_id,
                "RENDER_REQUEST_CONFLICT",
                EngineStage::RequestControl,
            )?);
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }
        if let Some(reservation) = state.busy_reservations.get(&request_id)
            && (reservation.command_digest != admitted.command_digest
                || reservation.deadline_epoch_millis != admitted.deadline_epoch_millis)
        {
            let response = TerminalResponse::problem(problem_bytes(
                &request_id,
                "RENDER_REQUEST_CONFLICT",
                EngineStage::RequestControl,
            )?);
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }
        if admitted.deadline_epoch_millis <= now_epoch_millis {
            state.busy_reservations.remove(&request_id);
            let response = TerminalResponse::problem(problem_bytes(
                &request_id,
                "RENDER_DEADLINE_EXCEEDED",
                EngineStage::RequestControl,
            )?);
            state.entries.insert(
                request_id,
                RegistryEntry {
                    command_digest: admitted.command_digest,
                    deadline_epoch_millis: admitted.deadline_epoch_millis,
                    retain_until_epoch_millis: now_epoch_millis
                        .saturating_add(TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS),
                    terminal_response: response.clone(),
                },
            );
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }
        if state.active.is_some() {
            if state.waiting.len() == WAITING_QUEUE_CAPACITY {
                state
                    .busy_reservations
                    .entry(request_id.clone())
                    .or_insert_with(|| BusyReservation {
                        command_digest: admitted.command_digest.clone(),
                        deadline_epoch_millis: admitted.deadline_epoch_millis,
                    });
                let response = TerminalResponse::problem(problem_bytes(
                    &request_id,
                    "RENDER_ENGINE_BUSY",
                    EngineStage::CommandAdmission,
                )?);
                drop(state);
                let _ = response_sender.send(response);
                return Ok(());
            }
            state.busy_reservations.remove(&request_id);
            let queue_expires_at_epoch_millis = admitted
                .deadline_epoch_millis
                .min(now_epoch_millis.saturating_add(WAITING_QUEUE_MILLIS));
            let command_digest = admitted.command_digest.clone();
            let deadline_epoch_millis = admitted.deadline_epoch_millis;
            state.waiting.push_back(QueuedRequest {
                admitted,
                accepted_at_epoch_millis: now_epoch_millis,
                response_sender: response_sender.clone(),
            });
            drop(state);
            self.spawn_queue_expiry(
                request_id,
                command_digest,
                deadline_epoch_millis,
                queue_expires_at_epoch_millis,
            );
            return Ok(());
        }
        state.busy_reservations.remove(&request_id);
        let cancellation = Arc::new(AtomicBool::new(false));
        state.active = Some(ActiveRequest {
            request_id: request_id.clone(),
            command_digest: admitted.command_digest.clone(),
            deadline_epoch_millis: admitted.deadline_epoch_millis,
            cancellation: Arc::clone(&cancellation),
        });
        drop(state);

        self.spawn_execution(
            admitted,
            now_epoch_millis,
            response_sender.clone(),
            cancellation,
        );
        Ok(())
    }

    fn spawn_execution(
        &self,
        admitted: AdmittedCommand,
        started_at_epoch_millis: i64,
        response_sender: mpsc::Sender<TerminalResponse>,
        cancellation: Arc<AtomicBool>,
    ) {
        let registry = self.clone();
        let request_id = admitted.command.request_id.clone();
        std::thread::spawn(move || {
            let control =
                RequestExecutionControl::live(admitted.deadline_epoch_millis, cancellation);
            let response = registry
                .executor
                .execute(&admitted, started_at_epoch_millis, &control)
                .unwrap_or_else(|_| {
                    TerminalResponse::problem(
                        problem_bytes(
                            &request_id,
                            "RENDER_INTERNAL_ERROR",
                            EngineStage::CommandAdmission,
                        )
                        .expect("admitted request id must produce a canonical problem"),
                    )
                });
            registry.finish_active(
                &request_id,
                &admitted.command_digest,
                admitted.deadline_epoch_millis,
                response,
                crate::now_epoch_millis(),
                &response_sender,
            );
        });
    }

    fn spawn_queue_expiry(
        &self,
        request_id: String,
        command_digest: String,
        deadline_epoch_millis: i64,
        queue_expires_at_epoch_millis: i64,
    ) {
        let registry = self.clone();
        std::thread::spawn(move || {
            let delay_millis =
                queue_expires_at_epoch_millis.saturating_sub(crate::now_epoch_millis());
            if delay_millis > 0 {
                std::thread::sleep(Duration::from_millis(delay_millis as u64));
            }
            registry.expire_waiting(
                &request_id,
                &command_digest,
                deadline_epoch_millis,
                crate::now_epoch_millis(),
            );
        });
    }

    fn handle_cancel(
        &self,
        payload: &[u8],
        now_epoch_millis: i64,
        response_sender: &mpsc::Sender<TerminalResponse>,
    ) -> Result<(), ProtocolError> {
        let cancel = parse_cancel(payload)?;
        let request_id = cancel.cancel.request_id.clone();
        let mut state = self.state.lock().expect("request registry mutex poisoned");
        state
            .entries
            .retain(|_, entry| entry.retain_until_epoch_millis > now_epoch_millis);
        state
            .busy_reservations
            .retain(|_, reservation| reservation.deadline_epoch_millis > now_epoch_millis);
        if let Some(existing) = state.entries.get(&request_id) {
            let response = if existing.command_digest == cancel.cancel.renderer_command_digest
                && existing.deadline_epoch_millis == cancel.deadline_epoch_millis
            {
                existing.terminal_response.clone()
            } else {
                TerminalResponse::problem(problem_bytes(
                    &request_id,
                    "RENDER_REQUEST_CONFLICT",
                    EngineStage::RequestControl,
                )?)
            };
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }

        if let Some(reservation) = state.busy_reservations.get(&request_id) {
            if reservation.command_digest != cancel.cancel.renderer_command_digest
                || reservation.deadline_epoch_millis != cancel.deadline_epoch_millis
            {
                let response = TerminalResponse::problem(problem_bytes(
                    &request_id,
                    "RENDER_REQUEST_CONFLICT",
                    EngineStage::RequestControl,
                )?);
                drop(state);
                let _ = response_sender.send(response);
                return Ok(());
            }
            state.busy_reservations.remove(&request_id);
            let code = if cancel.deadline_epoch_millis <= now_epoch_millis {
                "RENDER_DEADLINE_EXCEEDED"
            } else {
                "RENDER_CANCELLED"
            };
            let response = TerminalResponse::problem(problem_bytes(
                &request_id,
                code,
                EngineStage::RequestControl,
            )?);
            state.entries.insert(
                request_id,
                RegistryEntry {
                    command_digest: cancel.cancel.renderer_command_digest,
                    deadline_epoch_millis: cancel.deadline_epoch_millis,
                    retain_until_epoch_millis: cancel
                        .deadline_epoch_millis
                        .max(now_epoch_millis)
                        .saturating_add(TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS),
                    terminal_response: response.clone(),
                },
            );
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }

        if let Some(active) = state.active.as_ref()
            && active.request_id == request_id
        {
            if active.command_digest != cancel.cancel.renderer_command_digest
                || active.deadline_epoch_millis != cancel.deadline_epoch_millis
            {
                let response = TerminalResponse::problem(problem_bytes(
                    &request_id,
                    "RENDER_REQUEST_CONFLICT",
                    EngineStage::RequestControl,
                )?);
                drop(state);
                let _ = response_sender.send(response);
                return Ok(());
            }
            let code = if cancel.deadline_epoch_millis <= now_epoch_millis {
                "RENDER_DEADLINE_EXCEEDED"
            } else {
                "RENDER_CANCELLED"
            };
            let response = TerminalResponse::problem(problem_bytes(
                &request_id,
                code,
                EngineStage::RequestControl,
            )?);
            state
                .active
                .as_mut()
                .expect("active request must still exist")
                .cancellation
                .store(true, Ordering::SeqCst);
            state.entries.insert(
                request_id,
                RegistryEntry {
                    command_digest: cancel.cancel.renderer_command_digest,
                    deadline_epoch_millis: cancel.deadline_epoch_millis,
                    retain_until_epoch_millis: cancel
                        .deadline_epoch_millis
                        .max(now_epoch_millis)
                        .saturating_add(TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS),
                    terminal_response: response.clone(),
                },
            );
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }

        if let Some(position) = state
            .waiting
            .iter()
            .position(|waiting| waiting.admitted.command.request_id == request_id)
        {
            let waiting = &state.waiting[position];
            if waiting.admitted.command_digest != cancel.cancel.renderer_command_digest
                || waiting.admitted.deadline_epoch_millis != cancel.deadline_epoch_millis
            {
                let response = TerminalResponse::problem(problem_bytes(
                    &request_id,
                    "RENDER_REQUEST_CONFLICT",
                    EngineStage::RequestControl,
                )?);
                drop(state);
                let _ = response_sender.send(response);
                return Ok(());
            }
            state
                .waiting
                .remove(position)
                .expect("located queued request must remain removable");
            let code = if cancel.deadline_epoch_millis <= now_epoch_millis {
                "RENDER_DEADLINE_EXCEEDED"
            } else {
                "RENDER_CANCELLED"
            };
            let response = TerminalResponse::problem(problem_bytes(
                &request_id,
                code,
                EngineStage::RequestControl,
            )?);
            state.entries.insert(
                request_id,
                RegistryEntry {
                    command_digest: cancel.cancel.renderer_command_digest,
                    deadline_epoch_millis: cancel.deadline_epoch_millis,
                    retain_until_epoch_millis: cancel
                        .deadline_epoch_millis
                        .max(now_epoch_millis)
                        .saturating_add(TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS),
                    terminal_response: response.clone(),
                },
            );
            drop(state);
            let _ = response_sender.send(response);
            return Ok(());
        }

        let code = if cancel.deadline_epoch_millis <= now_epoch_millis {
            "RENDER_DEADLINE_EXCEEDED"
        } else {
            "RENDER_CANCELLED"
        };
        let response = TerminalResponse::problem(problem_bytes(
            &request_id,
            code,
            EngineStage::RequestControl,
        )?);
        state.entries.insert(
            request_id,
            RegistryEntry {
                command_digest: cancel.cancel.renderer_command_digest,
                deadline_epoch_millis: cancel.deadline_epoch_millis,
                retain_until_epoch_millis: now_epoch_millis
                    .saturating_add(PRE_COMMAND_CANCEL_TOMBSTONE_MILLIS),
                terminal_response: response.clone(),
            },
        );
        drop(state);
        let _ = response_sender.send(response);
        Ok(())
    }

    fn finish_active(
        &self,
        request_id: &str,
        command_digest: &str,
        deadline_epoch_millis: i64,
        response: TerminalResponse,
        sealed_at_epoch_millis: i64,
        response_sender: &mpsc::Sender<TerminalResponse>,
    ) {
        let mut state = self.state.lock().expect("request registry mutex poisoned");
        let Some(active) = state.active.as_ref() else {
            return;
        };
        if active.request_id != request_id
            || active.command_digest != command_digest
            || active.deadline_epoch_millis != deadline_epoch_millis
        {
            return;
        }
        let cancelled = active.cancellation.load(Ordering::SeqCst);
        state.active = None;
        let completed_response = if cancelled {
            None
        } else {
            let response = if deadline_epoch_millis <= sealed_at_epoch_millis {
                TerminalResponse::problem(
                    problem_bytes(
                        request_id,
                        "RENDER_DEADLINE_EXCEEDED",
                        EngineStage::OutputSeal,
                    )
                    .expect("admitted request id must produce a canonical problem"),
                )
            } else {
                response
            };
            state.entries.insert(
                request_id.to_owned(),
                RegistryEntry {
                    command_digest: command_digest.to_owned(),
                    deadline_epoch_millis,
                    retain_until_epoch_millis: deadline_epoch_millis
                        .max(sealed_at_epoch_millis)
                        .saturating_add(TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS),
                    terminal_response: response.clone(),
                },
            );
            Some(response)
        };
        let next = state.waiting.pop_front();
        let next_cancellation = next.as_ref().map(|_| Arc::new(AtomicBool::new(false)));
        if let Some(next) = next.as_ref() {
            state.active = Some(ActiveRequest {
                request_id: next.admitted.command.request_id.clone(),
                command_digest: next.admitted.command_digest.clone(),
                deadline_epoch_millis: next.admitted.deadline_epoch_millis,
                cancellation: Arc::clone(
                    next_cancellation
                        .as_ref()
                        .expect("next execution cancellation must exist"),
                ),
            });
        }
        drop(state);
        if let Some(completed_response) = completed_response {
            let _ = response_sender.send(completed_response);
        }
        if let Some(next) = next {
            self.spawn_execution(
                next.admitted,
                sealed_at_epoch_millis.max(next.accepted_at_epoch_millis),
                next.response_sender,
                next_cancellation.expect("next execution cancellation must exist"),
            );
        }
    }

    fn expire_waiting(
        &self,
        request_id: &str,
        command_digest: &str,
        deadline_epoch_millis: i64,
        sealed_at_epoch_millis: i64,
    ) {
        let mut state = self.state.lock().expect("request registry mutex poisoned");
        let Some(position) = state.waiting.iter().position(|waiting| {
            waiting.admitted.command.request_id == request_id
                && waiting.admitted.command_digest == command_digest
                && waiting.admitted.deadline_epoch_millis == deadline_epoch_millis
        }) else {
            return;
        };
        let waiting = state
            .waiting
            .remove(position)
            .expect("located queued request must remain removable");
        let response = TerminalResponse::problem(
            problem_bytes(
                request_id,
                "RENDER_DEADLINE_EXCEEDED",
                EngineStage::RequestControl,
            )
            .expect("admitted request id must produce a canonical problem"),
        );
        state.entries.insert(
            request_id.to_owned(),
            RegistryEntry {
                command_digest: command_digest.to_owned(),
                deadline_epoch_millis,
                retain_until_epoch_millis: deadline_epoch_millis
                    .max(sealed_at_epoch_millis)
                    .saturating_add(TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS),
                terminal_response: response.clone(),
            },
        );
        drop(state);
        let _ = waiting.response_sender.send(response);
    }
}

#[cfg(any(unix, test))]
fn resource_pipeline_problem_bytes(
    request_id: &str,
    problem: &ResourcePipelineProblem,
) -> Result<Vec<u8>, ProtocolError> {
    if problem.code() == "RENDER_CANCELLED" {
        return problem_bytes(request_id, problem.code(), EngineStage::RequestControl);
    }
    if matches!(
        problem.code(),
        "RENDER_DEADLINE_EXCEEDED" | "RENDER_INTERNAL_ERROR"
    ) {
        return problem_bytes(request_id, problem.code(), EngineStage::ResourcePreparation);
    }
    let Some(resource_id) = problem.resource_id() else {
        return problem_bytes(
            request_id,
            "RENDER_INTERNAL_ERROR",
            EngineStage::ResourcePreparation,
        );
    };
    if problem.code() == "RESOURCE_BUDGET_EXCEEDED" {
        let Some(limit_id) = problem.limit_id() else {
            return problem_bytes(
                request_id,
                "RENDER_INTERNAL_ERROR",
                EngineStage::ResourcePreparation,
            );
        };
        return resource_limit_problem_bytes(
            request_id,
            problem.code(),
            EngineStage::ResourcePreparation,
            resource_id,
            limit_id,
        );
    }
    resource_problem_bytes(
        request_id,
        problem.code(),
        EngineStage::ResourcePreparation,
        resource_id,
    )
}

#[cfg(all(any(unix, test), feature = "native-text-skia"))]
fn prepared_png_problem_bytes(
    request_id: &str,
    problem: &PreparedPngResultError,
) -> Result<Vec<u8>, ProtocolError> {
    match problem {
        PreparedPngResultError::Contract(_) => problem_bytes(
            request_id,
            "RENDER_INTERNAL_ERROR",
            EngineStage::CommandAdmission,
        ),
        PreparedPngResultError::Seal(_) => {
            problem_bytes(request_id, "RENDER_INTERNAL_ERROR", EngineStage::OutputSeal)
        }
        PreparedPngResultError::Engine(error) => engine_png_problem_bytes(request_id, error),
    }
}

#[cfg(any(unix, test))]
fn engine_png_problem_bytes(
    request_id: &str,
    error: &EnginePngError,
) -> Result<Vec<u8>, ProtocolError> {
    let code = error.code();
    let stage = protocol_engine_stage(error.problem_stage());
    match (error.occurrence_id(), error.resource_id(), error.limit_id()) {
        (Some(occurrence_id), Some(resource_id), None) => {
            occurrence_resource_problem_bytes(request_id, code, stage, occurrence_id, resource_id)
        }
        (None, None, Some(limit_id)) => limit_problem_bytes(request_id, code, stage, limit_id),
        (None, None, None) => problem_bytes(request_id, code, stage),
        _ => problem_bytes(
            request_id,
            "RENDER_INTERNAL_ERROR",
            EngineStage::DocumentAdmission,
        ),
    }
}

#[cfg(any(unix, test))]
fn protocol_engine_stage(stage: EngineProblemStage) -> EngineStage {
    match stage {
        EngineProblemStage::DocumentAdmission => EngineStage::DocumentAdmission,
        EngineProblemStage::OutputPreflight => EngineStage::OutputPreflight,
        EngineProblemStage::Layout => EngineStage::Layout,
        EngineProblemStage::Shaping => EngineStage::Shaping,
        EngineProblemStage::Rasterization => EngineStage::Rasterization,
        EngineProblemStage::Encoding => EngineStage::Encoding,
        EngineProblemStage::OutputSeal => EngineStage::OutputSeal,
    }
}

#[cfg(any(unix, test))]
fn now_epoch_millis() -> i64 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(duration) => i64::try_from(duration.as_millis()).unwrap_or(i64::MAX),
        Err(_) => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use renderweave_renderer_layout::preflight_layout;
    #[cfg(target_os = "linux")]
    use renderweave_renderer_protocol::client_hello_bytes;
    use renderweave_renderer_protocol::encode_frame;
    use serde_json::Value;
    use std::collections::VecDeque;
    use std::io::Cursor;
    use std::sync::Mutex;
    #[cfg(target_os = "linux")]
    use std::sync::mpsc;
    #[cfg(target_os = "linux")]
    use std::time::Duration;

    const VECTORS: &str = include_str!("../../../protocol-vectors-v1.json");
    const PROCESS_MANIFEST: &[u8] = include_bytes!("../../../process-manifest.json");
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
    const ASSET_VECTORS: &str = include_str!(
        "../../../../renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
    );
    const ASSET_FETCH_ORIGIN: &str = "https://render.internal.example";
    const ASSET_FETCH_ALLOWED_IPS: [&str; 1] = ["127.0.0.1"];
    #[cfg(all(target_os = "linux", feature = "native-text-skia"))]
    const PRODUCTION_TEXT_COMMAND: &[u8] =
        include_bytes!("../../../production-text-command-v1.json");
    #[cfg(all(target_os = "linux", feature = "native-text-skia"))]
    const PRODUCTION_TEXT_FONT: &[u8] = include_bytes!(
        "../../../../renderweave-asset/src/test/resources/asset-fixtures/minimal-ttf.ttf"
    );

    fn only_frame(response: &TerminalResponse) -> &Frame {
        assert_eq!(response.frames().len(), 1);
        &response.frames()[0]
    }

    #[test]
    fn output_surface_preflight_precedes_manifest_resource_fetch() {
        let (document, bodies) = renderable_resource_fixture();
        let mut document: Value = serde_json::from_str(&document).unwrap();
        document["canvas"]["widthPt"] = serde_json::json!(1_000_000);
        document["resources"][0]["fetchUrl"] =
            serde_json::json!("https://forbidden.example/internal/render-assets/token");
        let document = serde_json::to_string(&document).unwrap();
        let observations = Arc::new(Mutex::new(Vec::new()));
        let mut registry = request_registry_with(Arc::new(RecordingResourceFetcher {
            observations: Arc::clone(&observations),
            bodies,
            fail_first: false,
        }));

        let response = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command_with_document(&document).into_bytes(),
                },
                1_700_000_000_000,
            )
            .expect("output preflight rejection must be one terminal problem");

        assert!(observations.lock().unwrap().is_empty());
        let problem: Value = serde_json::from_slice(&only_frame(&response).payload).unwrap();
        assert_eq!("OUTPUT_BUDGET_EXCEEDED", problem["code"]);
        assert_eq!("OUTPUT_PREFLIGHT", problem["engineStage"]);
        assert_eq!(
            "rendererSurfaceAndOutput.surfaceEdgePixels",
            problem["parameters"]["limitId"]
        );
    }

    #[test]
    fn cancellation_after_an_uninterruptible_fetch_discards_it_before_decode_or_layout() {
        let (document, bodies) = renderable_resource_fixture();
        let admitted = parse_command(command_with_document(&document).as_bytes()).unwrap();
        let cancellation = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let executor = RequestExecutor {
            fetch_target_policy: FetchTargetPolicy::new(
                ASSET_FETCH_ORIGIN,
                ASSET_FETCH_PATH_PREFIX,
            )
            .unwrap(),
            resource_fetcher: Arc::new(CancellingResourceFetcher {
                cancellation: Arc::clone(&cancellation),
                bodies,
            }),
        };
        let control = RequestExecutionControl::fixed(
            admitted.deadline_epoch_millis,
            1_900_000_000_000,
            cancellation,
        );

        let response = executor
            .execute(&admitted, 1_900_000_000_000, &control)
            .expect("cancelled execution must return one terminal problem");

        let problem: Value = serde_json::from_slice(&only_frame(&response).payload).unwrap();
        assert_eq!("RENDER_CANCELLED", problem["code"]);
        assert_eq!("REQUEST_CONTROL", problem["engineStage"]);
    }

    #[cfg(all(target_os = "linux", feature = "native-text-skia"))]
    #[test]
    fn production_text_command_runs_through_the_concurrent_process_executor() {
        let payload = PRODUCTION_TEXT_COMMAND
            .strip_suffix(b"\n")
            .expect("shared Java command fixture must end in one LF")
            .to_vec();
        let resource_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let observations = Arc::new(Mutex::new(Vec::new()));
        let registry = concurrent_request_registry_with(Arc::new(RecordingResourceFetcher {
            observations: Arc::clone(&observations),
            bodies: BTreeMap::from([(resource_id.to_owned(), PRODUCTION_TEXT_FONT.to_vec())]),
            fail_first: false,
        }));
        let (response_sender, response_receiver) = mpsc::channel();
        let command = Frame {
            frame_type: FrameType::Command,
            payload,
        };

        registry
            .handle(command.clone(), 1_900_000_000_000, &response_sender)
            .expect("production process must accept the exact command frame");
        let first = response_receiver
            .recv_timeout(Duration::from_secs(30))
            .expect("production process must seal one terminal");
        registry
            .handle(command, 1_900_000_000_001, &response_sender)
            .expect("same command must replay its terminal");
        let second = response_receiver
            .recv_timeout(Duration::from_secs(30))
            .expect("production process replay must return one terminal");

        assert_eq!(vec![resource_id], *observations.lock().unwrap());
        assert_eq!(2, first.frames().len());
        assert_eq!(FrameType::ResultMetadata, first.frames()[0].frame_type);
        assert_eq!(FrameType::ResultImage, first.frames()[1].frame_type);
        let metadata: Value = serde_json::from_slice(&first.frames()[0].payload)
            .expect("production process metadata must be closed JSON");
        assert_eq!(18_582, metadata["byteLength"]);
        assert_eq!(
            "77c3a0195d424998a55595a52b305344c86efe5f770884f7d1cd639c63be936b",
            metadata["contentSha256"]
        );
        assert_eq!(&first.frames()[1].payload[16..24], b"\x89PNG\r\n\x1a\n");
        assert_eq!(first.frames(), second.frames());
        assert_eq!(18_582 + 16, first.frames()[1].payload.len());
    }

    #[cfg(all(
        target_os = "linux",
        feature = "native-text-skia",
        feature = "native-jpeg-turbo"
    ))]
    #[test]
    fn production_text_jpeg_command_runs_through_the_concurrent_process_executor() {
        let payload = String::from_utf8(
            PRODUCTION_TEXT_COMMAND
                .strip_suffix(b"\n")
                .expect("shared Java command fixture must end in one LF")
                .to_vec(),
        )
        .expect("shared Java command fixture must be UTF-8")
        .replace(
            "\"output\":{\"profile\":\"renderweave-output-png/1.0\",\"dpi\":96}",
            "\"output\":{\"profile\":\"renderweave-output-jpeg/1.0\",\"dpi\":96,\"quality\":90}",
        )
        .into_bytes();
        let resource_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let observations = Arc::new(Mutex::new(Vec::new()));
        let registry = concurrent_request_registry_with(Arc::new(RecordingResourceFetcher {
            observations: Arc::clone(&observations),
            bodies: BTreeMap::from([(resource_id.to_owned(), PRODUCTION_TEXT_FONT.to_vec())]),
            fail_first: false,
        }));
        let (response_sender, response_receiver) = mpsc::channel();
        let command = Frame {
            frame_type: FrameType::Command,
            payload,
        };

        registry
            .handle(command.clone(), 1_900_000_000_000, &response_sender)
            .expect("production process must accept the exact JPEG command frame");
        let first = response_receiver
            .recv_timeout(Duration::from_secs(30))
            .expect("production process must seal one JPEG terminal");
        registry
            .handle(command, 1_900_000_000_001, &response_sender)
            .expect("same JPEG command must replay its terminal");
        let second = response_receiver
            .recv_timeout(Duration::from_secs(30))
            .expect("production process JPEG replay must return one terminal");

        assert_eq!(vec![resource_id], *observations.lock().unwrap());
        assert_eq!(2, first.frames().len());
        assert_eq!(FrameType::ResultMetadata, first.frames()[0].frame_type);
        assert_eq!(FrameType::ResultImage, first.frames()[1].frame_type);
        let metadata: Value = serde_json::from_slice(&first.frames()[0].payload)
            .expect("production process JPEG metadata must be closed JSON");
        assert_eq!("renderweave-output-jpeg/1.0", metadata["outputProfile"]);
        assert_eq!("JPEG", metadata["format"]);
        assert_eq!("image/jpeg", metadata["mediaType"]);
        assert_eq!(96, metadata["widthPx"]);
        assert_eq!(48, metadata["heightPx"]);
        assert_eq!(96, metadata["dpi"]);
        assert_eq!(90, metadata["quality"]);
        let byte_length = metadata["byteLength"]
            .as_u64()
            .expect("JPEG byte length must be uint64") as usize;
        let content_sha256 = metadata["contentSha256"]
            .as_str()
            .expect("JPEG digest must be lowercase SHA-256");
        assert_eq!(64, content_sha256.len());
        assert!(
            content_sha256
                .bytes()
                .all(|value| value.is_ascii_digit() || (b'a'..=b'f').contains(&value))
        );
        let image_payload = &first.frames()[1].payload;
        assert_eq!(byte_length + 16, image_payload.len());
        assert_eq!(&image_payload[16..18], b"\xff\xd8");
        assert_eq!(&image_payload[image_payload.len() - 2..], b"\xff\xd9");
        assert_eq!(first.frames(), second.frames());
    }

    #[cfg(all(target_os = "linux", feature = "native-text-skia"))]
    #[test]
    fn production_deadline_at_output_seal_discards_the_complete_image() {
        let payload = PRODUCTION_TEXT_COMMAND
            .strip_suffix(b"\n")
            .expect("shared Java command fixture must end in one LF")
            .to_vec();
        let resource_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let observations = Arc::new(Mutex::new(Vec::new()));
        let mut registry = RequestRegistry::new(
            FetchTargetPolicy::new(ASSET_FETCH_ORIGIN, ASSET_FETCH_PATH_PREFIX)
                .expect("fixed internal fetch target"),
            Arc::new(RecordingResourceFetcher {
                observations: Arc::clone(&observations),
                bodies: BTreeMap::from([(resource_id.to_owned(), PRODUCTION_TEXT_FONT.to_vec())]),
                fail_first: false,
            }),
        );
        let mut times = VecDeque::from([1_900_000_000_000_i64, 2_000_000_000_000_i64]);

        let response = registry
            .handle_with_clock(
                Frame {
                    frame_type: FrameType::Command,
                    payload,
                },
                &mut || times.pop_front().expect("exactly two clock reads"),
            )
            .expect("elapsed output seal must produce one terminal problem");

        assert_eq!(vec![resource_id], *observations.lock().unwrap());
        let problem: Value = serde_json::from_slice(&only_frame(&response).payload)
            .expect("deadline problem must be closed JSON");
        assert_eq!(FrameType::Problem, only_frame(&response).frame_type);
        assert_eq!("RENDER_DEADLINE_EXCEEDED", problem["code"]);
        assert_eq!("OUTPUT_SEAL", problem["engineStage"]);
    }

    #[cfg(all(target_os = "linux", feature = "native-text-skia"))]
    #[test]
    fn production_engine_failures_map_to_closed_problem_codes_and_stages() {
        use renderweave_renderer_engine::EnginePngUnsupported;

        let request_id = "123e4567-e89b-42d3-a456-426614174000";
        let occurrence_id = "rwocc_0000000000000001";
        let resource_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let cases = [
            (
                EnginePngError::Contract("sealed document invariant"),
                "RENDER_INTERNAL_ERROR",
                "DOCUMENT_ADMISSION",
                false,
            ),
            (
                EnginePngError::Unsupported(EnginePngUnsupported::TextPaint),
                "RENDER_INTERNAL_ERROR",
                "DOCUMENT_ADMISSION",
                false,
            ),
            (
                EnginePngError::Layout,
                "RENDER_INTERNAL_ERROR",
                "LAYOUT",
                false,
            ),
            (
                EnginePngError::RasterAllocation,
                "RENDER_INTERNAL_ERROR",
                "RASTERIZATION",
                false,
            ),
            (
                EnginePngError::FontGlyphMissing {
                    occurrence_id: occurrence_id.into(),
                    resource_id: resource_id.into(),
                },
                "FONT_GLYPH_MISSING",
                "SHAPING",
                true,
            ),
            (
                EnginePngError::TextShaping {
                    occurrence_id: occurrence_id.into(),
                    resource_id: resource_id.into(),
                },
                "RENDER_INTERNAL_ERROR",
                "SHAPING",
                true,
            ),
            (
                EnginePngError::TextRaster {
                    occurrence_id: occurrence_id.into(),
                    resource_id: resource_id.into(),
                },
                "RENDER_INTERNAL_ERROR",
                "RASTERIZATION",
                true,
            ),
            (
                EnginePngError::Interrupted {
                    interruption: EngineInterruption::Cancelled,
                    checkpoint: EngineCheckpoint::Shaping,
                },
                "RENDER_CANCELLED",
                "SHAPING",
                false,
            ),
            (
                EnginePngError::Interrupted {
                    interruption: EngineInterruption::DeadlineExceeded,
                    checkpoint: EngineCheckpoint::Encoding,
                },
                "RENDER_DEADLINE_EXCEEDED",
                "ENCODING",
                false,
            ),
        ];

        for (error, code, stage, has_locators) in cases {
            let payload = engine_png_problem_bytes(request_id, &error)
                .expect("typed Engine failure must project to one closed problem");
            let problem: Value = serde_json::from_slice(&payload).expect("closed problem JSON");
            assert_eq!(code, problem["code"]);
            assert_eq!(stage, problem["engineStage"]);
            assert_eq!(has_locators, problem.get("occurrenceId").is_some());
            assert_eq!(has_locators, problem.get("resourceId").is_some());
        }

        for (error, stage) in [
            (
                PreparedPngResultError::Contract("command invariant"),
                "COMMAND_ADMISSION",
            ),
            (
                PreparedPngResultError::Seal(ProtocolError::Invalid("seal invariant")),
                "OUTPUT_SEAL",
            ),
        ] {
            let payload = prepared_png_problem_bytes(request_id, &error)
                .expect("prepared result failure must project to one closed problem");
            let problem: Value = serde_json::from_slice(&payload).expect("closed problem JSON");
            assert_eq!("RENDER_INTERNAL_ERROR", problem["code"]);
            assert_eq!(stage, problem["engineStage"]);
        }
    }

    #[test]
    fn daemon_requires_an_explicit_socket_manifest_fetch_origin_and_frame_limit() {
        assert!(DaemonConfiguration::new("", "", "", ASSET_FETCH_ALLOWED_IPS, 0).is_err());
        assert!(
            DaemonConfiguration::new(
                "/tmp/rw.sock",
                "",
                ASSET_FETCH_ORIGIN,
                ASSET_FETCH_ALLOWED_IPS,
                1
            )
            .is_err()
        );
        assert!(
            DaemonConfiguration::new(
                "/tmp/rw.sock",
                "/tmp/manifest",
                "",
                ASSET_FETCH_ALLOWED_IPS,
                1
            )
            .is_err()
        );
        assert!(
            DaemonConfiguration::new(
                "/tmp/rw.sock",
                "/tmp/manifest",
                "http://render.internal.example",
                ASSET_FETCH_ALLOWED_IPS,
                1
            )
            .is_err()
        );
        assert!(
            DaemonConfiguration::new(
                "/tmp/rw.sock",
                "/tmp/manifest",
                ASSET_FETCH_ORIGIN,
                ASSET_FETCH_ALLOWED_IPS,
                0
            )
            .is_err()
        );
        assert!(
            DaemonConfiguration::new(
                "/tmp/rw.sock",
                "/tmp/manifest",
                ASSET_FETCH_ORIGIN,
                std::iter::empty::<&str>(),
                1
            )
            .is_err()
        );
        assert!(
            DaemonConfiguration::new(
                "/tmp/rw.sock",
                "/tmp/manifest",
                ASSET_FETCH_ORIGIN,
                ASSET_FETCH_ALLOWED_IPS,
                1
            )
            .is_ok()
        );
    }

    #[test]
    fn registry_replays_exact_terminal_and_rejects_same_id_drift() {
        let command = vector_json("png-command");
        let mut registry = request_registry();
        let first = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        assert_eq!(only_frame(&first).frame_type, FrameType::Problem);
        assert_eq!(
            only_frame(&first).payload,
            vector_json("problem").as_bytes()
        );

        let replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                1_800_000_000_001,
            )
            .unwrap();
        assert_eq!(only_frame(&replay).payload, only_frame(&first).payload);

        let drifted = command.replace("\"layoutTrace\":false", "\"layoutTrace\":true");
        let conflict = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: drifted.into_bytes(),
                },
                1_800_000_000_002,
            )
            .unwrap();
        let conflict: Value = serde_json::from_slice(&only_frame(&conflict).payload).unwrap();
        assert_eq!(conflict["code"], "RENDER_REQUEST_CONFLICT");
        assert_eq!(conflict["engineStage"], "REQUEST_CONTROL");
    }

    #[test]
    fn terminal_retention_starts_at_actual_seal_and_replay_does_not_renew_it() {
        let command = vector_json("png-command");
        let deadline = parse_command(command.as_bytes())
            .unwrap()
            .deadline_epoch_millis;
        let sealed_at = deadline + 10_000;
        let mut times = VecDeque::from([deadline - 1, sealed_at]);
        let mut clock = || times.pop_front().expect("scripted clock exhausted");
        let mut registry = request_registry();

        let first = registry
            .handle_with_clock(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                &mut clock,
            )
            .unwrap();
        assert_eq!(only_frame(&first).frame_type, FrameType::Problem);

        let early_replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                sealed_at + 1,
            )
            .unwrap();
        assert_eq!(
            only_frame(&early_replay).payload,
            only_frame(&first).payload
        );

        let last_replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                sealed_at + 299_999,
            )
            .unwrap();
        assert_eq!(only_frame(&last_replay).payload, only_frame(&first).payload);

        let expired = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.into_bytes(),
                },
                sealed_at + 300_000,
            )
            .unwrap();
        let expired: Value = serde_json::from_slice(&only_frame(&expired).payload).unwrap();
        assert_eq!(expired["code"], "RENDER_DEADLINE_EXCEEDED");
        assert_ne!(
            only_frame(&first).payload,
            serde_json::to_vec(&expired).unwrap()
        );
    }

    #[test]
    fn terminal_retention_starts_at_command_deadline_when_it_is_later_than_seal() {
        let command = vector_json("png-command");
        let deadline = parse_command(command.as_bytes())
            .unwrap()
            .deadline_epoch_millis;
        let mut times = VecDeque::from([deadline - 20_000, deadline - 10_000]);
        let mut clock = || times.pop_front().expect("scripted clock exhausted");
        let mut registry = request_registry();

        let first = registry
            .handle_with_clock(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                &mut clock,
            )
            .unwrap();
        let last_replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                deadline + 299_999,
            )
            .unwrap();
        assert_eq!(only_frame(&last_replay).payload, only_frame(&first).payload);

        let expired = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.into_bytes(),
                },
                deadline + 300_000,
            )
            .unwrap();
        let expired: Value = serde_json::from_slice(&only_frame(&expired).payload).unwrap();
        assert_eq!(expired["code"], "RENDER_DEADLINE_EXCEEDED");
    }

    #[test]
    fn registry_rejects_digest_valid_but_contract_invalid_document_before_profile_lookup() {
        let command = vector_json("png-command");
        let document = vector_document("png-command");
        let invalid_document = document.replace("\"backgroundColor\":\"#00000000\",", "");
        let invalid_digest = renderweave_renderer_protocol::digest_with_domain(
            renderweave_renderer_protocol::DOCUMENT_DIGEST_DOMAIN,
            invalid_document.as_bytes(),
        );
        let old_digest = vector_value("png-command", "renderDocumentDigest");
        let invalid_command = command
            .replace(&document, &invalid_document)
            .replace(&old_digest, &invalid_digest);

        let mut registry = request_registry();
        let response = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: invalid_command.into_bytes(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        let problem: Value = serde_json::from_slice(&only_frame(&response).payload).unwrap();
        assert_eq!(problem["code"], "RENDER_INTERNAL_ERROR");
        assert_eq!(problem["engineStage"], "DOCUMENT_ADMISSION");
    }

    #[test]
    fn registry_rejects_admitted_but_layout_invalid_document_after_manifest_resources() {
        let (resource_document, bodies) = renderable_resource_fixture();
        let resource_document: Value = serde_json::from_str(&resource_document).unwrap();
        let mut layout_invalid: Value = serde_json::from_str(ALL_KINDS).unwrap();
        layout_invalid["resources"] = resource_document["resources"].clone();
        let layout_invalid_document = serde_json::to_string(&layout_invalid).unwrap();
        let admitted = validate_render_document(&layout_invalid_document).unwrap();
        assert!(preflight_layout(&admitted).is_err());
        let invalid_command = command_with_document(&layout_invalid_document);
        let observations = Arc::new(Mutex::new(Vec::new()));

        let mut registry = request_registry_with(Arc::new(RecordingResourceFetcher {
            observations: Arc::clone(&observations),
            bodies,
            fail_first: false,
        }));
        let response = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: invalid_command.into_bytes(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        let problem: Value = serde_json::from_slice(&only_frame(&response).payload).unwrap();
        assert_eq!(2, observations.lock().unwrap().len());
        assert_eq!(problem["code"], "RENDER_INTERNAL_ERROR");
        assert_eq!(problem["engineStage"], "DOCUMENT_ADMISSION");
    }

    #[test]
    fn registry_returns_first_insufficient_resource_lease_and_replays_it() {
        let document = all_kinds_with_expiries([4_090_912_501; 2]);
        let command = command_with_document(&document);
        let mut registry = request_registry();
        let first = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        let problem: Value = serde_json::from_slice(&only_frame(&first).payload).unwrap();
        assert_eq!(problem["code"], "RESOURCE_LEASE_EXPIRED");
        assert_eq!(problem["engineStage"], "COMMAND_ADMISSION");
        assert_eq!(
            problem["resourceId"],
            "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        assert_eq!(problem["parameters"], serde_json::json!({}));
        assert!(problem.get("occurrenceId").is_none());

        let replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.into_bytes(),
                },
                1_800_000_000_001,
            )
            .unwrap();
        assert_eq!(only_frame(&replay).payload, only_frame(&first).payload);
    }

    #[test]
    fn registry_rejects_non_allowlisted_resource_target_before_layout() {
        let mut document: Value =
            serde_json::from_str(&all_kinds_with_expiries([4_090_912_502; 2])).unwrap();
        document["resources"][0]["fetchUrl"] =
            Value::String("https://evil.example/internal/render-assets/token".to_owned());
        let document = serde_json::to_string(&document).unwrap();
        let command = command_with_document(&document);
        let mut registry = request_registry();

        let response = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.into_bytes(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        let problem: Value = serde_json::from_slice(&only_frame(&response).payload).unwrap();
        assert_eq!(problem["code"], "RENDER_INTERNAL_ERROR");
        assert_eq!(problem["engineStage"], "RESOURCE_PREPARATION");
        assert!(problem.get("resourceId").is_none());
        assert_eq!(problem["parameters"], serde_json::json!({}));
    }

    #[test]
    fn registry_fetches_admitted_resources_in_manifest_order_before_profile_lookup() {
        let (document, bodies) = renderable_resource_fixture();
        let command = command_with_document(&document);
        let observations = Arc::new(Mutex::new(Vec::new()));
        let fetcher = Arc::new(RecordingResourceFetcher {
            observations: observations.clone(),
            bodies,
            fail_first: false,
        });
        let mut registry = request_registry_with(fetcher);

        let response = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.into_bytes(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        let problem: Value = serde_json::from_slice(&only_frame(&response).payload).unwrap();
        assert_eq!(problem["code"], "RENDER_INTERNAL_ERROR");
        assert_eq!(problem["engineStage"], "COMMAND_ADMISSION");
        assert_eq!(
            observations.lock().unwrap().as_slice(),
            &[
                "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".to_owned(),
                "rwres_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".to_owned(),
            ]
        );
    }

    #[test]
    fn registry_maps_fetch_failure_to_safe_resource_problem_and_replays_without_refetch() {
        let (document, bodies) = renderable_resource_fixture();
        let command = command_with_document(&document);
        let observations = Arc::new(Mutex::new(Vec::new()));
        let fetcher = Arc::new(RecordingResourceFetcher {
            observations: observations.clone(),
            bodies,
            fail_first: true,
        });
        let mut registry = request_registry_with(fetcher);

        let first = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        let problem: Value = serde_json::from_slice(&only_frame(&first).payload).unwrap();
        assert_eq!(problem["code"], "FETCH_FAILED");
        assert_eq!(problem["engineStage"], "RESOURCE_PREPARATION");
        assert_eq!(
            problem["resourceId"],
            "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        assert_eq!(problem["parameters"], serde_json::json!({}));

        let replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.into_bytes(),
                },
                1_800_000_000_001,
            )
            .unwrap();
        assert_eq!(only_frame(&replay).payload, only_frame(&first).payload);
        assert_eq!(observations.lock().unwrap().len(), 1);
    }

    #[test]
    fn registry_prepares_each_resource_before_fetching_the_next_and_replays_failure() {
        let (document, bodies) = media_mismatch_resource_fixture();
        let command = command_with_document(&document);
        let observations = Arc::new(Mutex::new(Vec::new()));
        let fetcher = Arc::new(RecordingResourceFetcher {
            observations: observations.clone(),
            bodies,
            fail_first: false,
        });
        let mut registry = request_registry_with(fetcher);

        let first = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        let problem: Value = serde_json::from_slice(&only_frame(&first).payload).unwrap();
        assert_eq!(problem["code"], "MEDIA_MISMATCH");
        assert_eq!(problem["engineStage"], "RESOURCE_PREPARATION");
        assert_eq!(
            problem["resourceId"],
            "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        assert_eq!(observations.lock().unwrap().len(), 1);

        let replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.into_bytes(),
                },
                1_800_000_000_001,
            )
            .unwrap();
        assert_eq!(only_frame(&replay).payload, only_frame(&first).payload);
        assert_eq!(observations.lock().unwrap().len(), 1);
    }

    #[test]
    fn sealed_terminal_writes_exact_metadata_then_image_frames() {
        let image_bytes = decode_base64(&vector_value("png-result-metadata", "imageBase64"));
        let sealed = seal_result(ResultSealInput {
            request_id: "123e4567-e89b-42d3-a456-426614174000",
            renderer_profile: "renderweave-renderer/1.0",
            dsl_version: "renderweave-render/1.0",
            layout_profile: "renderweave-layout/1.0",
            width_px: 1,
            height_px: 1,
            output: ResultOutputSelection::Png { dpi: 96 },
            image_bytes,
        })
        .unwrap();
        let response = TerminalResponse::sealed_result(sealed);
        assert_eq!(response.frames().len(), 2);
        assert_eq!(response.frames()[0].frame_type, FrameType::ResultMetadata);
        assert_eq!(response.frames()[1].frame_type, FrameType::ResultImage);

        let mut actual = Vec::new();
        response.write_to(&mut actual).unwrap();
        let mut expected =
            decode_base64(&vector_value("png-result-metadata", "expectedFrameBase64"));
        expected.extend_from_slice(&decode_base64(&vector_value(
            "png-result-image",
            "expectedFrameBase64",
        )));
        assert_eq!(actual, expected);
    }

    #[test]
    fn pre_command_cancel_is_terminal_and_never_emits_result() {
        let mut registry = request_registry();
        let cancel = vector_json("cancel");
        let response = registry
            .handle(
                Frame {
                    frame_type: FrameType::Cancel,
                    payload: cancel.into_bytes(),
                },
                1_800_000_000_000,
            )
            .unwrap();
        assert_eq!(only_frame(&response).frame_type, FrameType::Problem);
        let problem: Value = serde_json::from_slice(&only_frame(&response).payload).unwrap();
        assert_eq!(problem["code"], "RENDER_CANCELLED");
        assert_eq!(problem["engineStage"], "REQUEST_CONTROL");
    }

    #[test]
    fn pre_command_cancel_retention_fails_closed_when_exact_expiry_overflows() {
        let mut registry = request_registry();
        let cancel = vector_json("cancel");

        let error = registry
            .handle(
                Frame {
                    frame_type: FrameType::Cancel,
                    payload: cancel.into_bytes(),
                },
                i64::MAX - 59_999,
            )
            .expect_err("an inexact saturated tombstone expiry must not be accepted");
        assert_eq!(
            error.to_string(),
            "pre-command cancel tombstone retention deadline overflow"
        );
    }

    #[test]
    fn pre_command_cancel_tombstone_expires_exactly_and_replays_do_not_renew_it() {
        let cancel = vector_json("cancel");
        let admitted = parse_cancel(cancel.as_bytes()).unwrap();
        let created_at = admitted.deadline_epoch_millis - 120_000;
        let conflicting_cancel = cancel.replace(
            &admitted.cancel.renderer_command_digest,
            &format!("sha256:{}", "0".repeat(64)),
        );
        let mut registry = request_registry();

        let first = registry
            .handle(
                Frame {
                    frame_type: FrameType::Cancel,
                    payload: cancel.as_bytes().to_vec(),
                },
                created_at,
            )
            .unwrap();
        let replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Cancel,
                    payload: cancel.as_bytes().to_vec(),
                },
                created_at + 1,
            )
            .unwrap();
        assert_eq!(only_frame(&replay).payload, only_frame(&first).payload);

        let conflict = registry
            .handle(
                Frame {
                    frame_type: FrameType::Cancel,
                    payload: conflicting_cancel.as_bytes().to_vec(),
                },
                created_at + 30_000,
            )
            .unwrap();
        let conflict: Value = serde_json::from_slice(&only_frame(&conflict).payload).unwrap();
        assert_eq!(conflict["code"], "RENDER_REQUEST_CONFLICT");

        let last_replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Cancel,
                    payload: cancel.into_bytes(),
                },
                created_at + 59_999,
            )
            .unwrap();
        assert_eq!(only_frame(&last_replay).payload, only_frame(&first).payload);

        let after_exact_expiry = registry
            .handle(
                Frame {
                    frame_type: FrameType::Cancel,
                    payload: conflicting_cancel.into_bytes(),
                },
                created_at + 60_000,
            )
            .unwrap();
        let after_exact_expiry: Value =
            serde_json::from_slice(&only_frame(&after_exact_expiry).payload).unwrap();
        assert_eq!(after_exact_expiry["code"], "RENDER_CANCELLED");
        assert_eq!(after_exact_expiry["engineStage"], "REQUEST_CONTROL");
    }

    #[test]
    fn in_memory_connection_requires_hello_before_command() {
        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let command = vector_json("png-command");
        let input = encode_frame(FrameType::Command, command.as_bytes()).unwrap();
        let mut connection = MemoryDuplex::new(input);
        let mut registry = request_registry();
        assert!(serve_connection(&mut connection, &identity, &mut registry, 4096).is_err());
        assert!(connection.output.is_empty());
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_round_trip_handshake_command_and_replay() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t22-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(Arc::new(SuccessResourceFetcher));
            serve_unix_connection(stream, &server_identity, &registry, 4096).unwrap();
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        let hello = read_frame(&mut client, 4096).unwrap();
        assert_eq!(hello.frame_type, FrameType::ServerHello);
        assert_eq!(hello.payload, vector_json("server-hello").as_bytes());

        let command = vector_json("png-command");
        for _ in 0..2 {
            write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
            let problem = read_frame(&mut client, 4096).unwrap();
            assert_eq!(problem.frame_type, FrameType::Problem);
            assert_eq!(problem.payload, vector_json("problem").as_bytes());
        }
        client.shutdown(Shutdown::Both).unwrap();
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_active_cancel_seals_cancel_before_blocked_work_can_finish() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-active-cancel-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, bodies) = renderable_resource_fixture();
        let command = command_with_document(&document);
        let admitted = parse_command(command.as_bytes()).unwrap();
        let cancel = serde_json::to_vec(&renderweave_renderer_protocol::Cancel {
            contract_version: "renderweave-render-cancel/1.0".to_owned(),
            request_id: admitted.command.request_id.clone(),
            renderer_command_digest: admitted.command_digest.clone(),
            deadline_at: admitted.command.deadline_at.clone(),
        })
        .unwrap();
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingResourceFetcher {
            bodies,
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("resource fetch must start before cancellation");
        write_frame(&mut client, FrameType::Cancel, &cancel).unwrap();
        release_sender.send(()).unwrap();

        let terminal = read_frame(&mut client, 4096).unwrap();
        let terminal_payload: Value = serde_json::from_slice(&terminal.payload).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(terminal.frame_type, FrameType::Problem);
        assert_eq!(terminal_payload["code"], "RENDER_CANCELLED");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_reserves_four_fifo_positions_before_reporting_engine_busy() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-capacity-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, bodies) = renderable_resource_fixture();
        let base_command = command_with_document(&document);
        let commands = (0..6)
            .map(|index| {
                command_with_request_id(
                    &base_command,
                    &format!("123e4567-e89b-42d3-a456-42661417400{index}"),
                )
            })
            .collect::<Vec<_>>();
        let expected_busy_request_id = parse_command(commands.last().unwrap().as_bytes())
            .unwrap()
            .command
            .request_id;
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingResourceFetcher {
            bodies,
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, commands[0].as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("the active request must occupy the execution slot");
        for command in &commands[1..] {
            write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        }

        let busy = read_frame(&mut client, 4096).unwrap();
        let busy_payload: Value = serde_json::from_slice(&busy.payload).unwrap();
        release_sender.send(()).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(busy.frame_type, FrameType::Problem);
        assert_eq!(busy_payload["code"], "RENDER_ENGINE_BUSY");
        assert_eq!(busy_payload["engineStage"], "COMMAND_ADMISSION");
        assert_eq!(busy_payload["requestId"], expected_busy_request_id);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_queued_cancel_removes_request_before_fifo_execution() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-queued-cancel-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, bodies) = renderable_resource_fixture();
        let base_command = command_with_document(&document);
        let active_command =
            command_with_request_id(&base_command, "123e4567-e89b-42d3-a456-426614174010");
        let cancelled_command =
            command_with_request_id(&base_command, "123e4567-e89b-42d3-a456-426614174011");
        let next_command =
            command_with_request_id(&base_command, "123e4567-e89b-42d3-a456-426614174012");
        let cancel = cancel_for_command(&cancelled_command);
        let active_request_id = parse_command(active_command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let cancelled_request_id = parse_command(cancelled_command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let next_request_id = parse_command(next_command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingResourceFetcher {
            bodies,
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, active_command.as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("the active request must occupy the execution slot");
        write_frame(
            &mut client,
            FrameType::Command,
            cancelled_command.as_bytes(),
        )
        .unwrap();
        write_frame(&mut client, FrameType::Command, next_command.as_bytes()).unwrap();
        write_frame(&mut client, FrameType::Cancel, &cancel).unwrap();

        let cancelled = read_frame(&mut client, 4096).unwrap();
        let cancelled_payload: Value = serde_json::from_slice(&cancelled.payload).unwrap();
        release_sender.send(()).unwrap();
        let active = read_frame(&mut client, 4096).unwrap();
        let active_payload: Value = serde_json::from_slice(&active.payload).unwrap();
        let next = read_frame(&mut client, 4096).unwrap();
        let next_payload: Value = serde_json::from_slice(&next.payload).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(cancelled_payload["code"], "RENDER_CANCELLED");
        assert_eq!(cancelled_payload["requestId"], cancelled_request_id);
        assert_eq!(active_payload["requestId"], active_request_id);
        assert_eq!(next_payload["requestId"], next_request_id);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_queued_request_expires_after_five_second_wait_without_slot() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-queue-timeout-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, bodies) = renderable_resource_fixture();
        let base_command = command_with_document(&document);
        let active_command =
            command_with_request_id(&base_command, "123e4567-e89b-42d3-a456-426614174020");
        let queued_command =
            command_with_request_id(&base_command, "123e4567-e89b-42d3-a456-426614174021");
        let queued_request_id = parse_command(queued_command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingResourceFetcher {
            bodies,
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(7)))
            .unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, active_command.as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("the active request must occupy the execution slot");
        write_frame(&mut client, FrameType::Command, queued_command.as_bytes()).unwrap();

        let queued_terminal = read_frame(&mut client, 4096);
        release_sender.send(()).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();
        let queued_terminal =
            queued_terminal.expect("queued request must expire while the slot remains occupied");
        let queued_payload: Value = serde_json::from_slice(&queued_terminal.payload).unwrap();

        assert_eq!(queued_terminal.frame_type, FrameType::Problem);
        assert_eq!(queued_payload["code"], "RENDER_DEADLINE_EXCEEDED");
        assert_eq!(queued_payload["requestId"], queued_request_id);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_busy_reservation_rejects_same_request_id_digest_drift() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-busy-reservation-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, bodies) = renderable_resource_fixture();
        let base_command = command_with_document(&document);
        let commands = (0..6)
            .map(|index| {
                command_with_request_id(
                    &base_command,
                    &format!("123e4567-e89b-42d3-a456-42661417403{index}"),
                )
            })
            .collect::<Vec<_>>();
        let drifted_busy_command = commands[5].replace(r#""dpi":96"#, r#""dpi":97"#);
        assert_ne!(
            parse_command(commands[5].as_bytes())
                .unwrap()
                .command_digest,
            parse_command(drifted_busy_command.as_bytes())
                .unwrap()
                .command_digest
        );
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingResourceFetcher {
            bodies,
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, commands[0].as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("the active request must occupy the execution slot");
        for command in &commands[1..5] {
            write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        }
        write_frame(&mut client, FrameType::Command, commands[5].as_bytes()).unwrap();
        let busy = read_frame(&mut client, 4096).unwrap();
        let busy_payload: Value = serde_json::from_slice(&busy.payload).unwrap();
        write_frame(
            &mut client,
            FrameType::Command,
            drifted_busy_command.as_bytes(),
        )
        .unwrap();
        let conflict = read_frame(&mut client, 4096).unwrap();
        let conflict_payload: Value = serde_json::from_slice(&conflict.payload).unwrap();
        release_sender.send(()).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(busy_payload["code"], "RENDER_ENGINE_BUSY");
        assert_eq!(conflict_payload["code"], "RENDER_REQUEST_CONFLICT");
        assert_eq!(conflict_payload["requestId"], busy_payload["requestId"]);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_active_work_cannot_seal_after_absolute_deadline() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-active-deadline-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, _) = renderable_resource_fixture();
        let command = command_with_document(&document);
        let deadline_epoch_millis = now_epoch_millis().saturating_add(3_000);
        let command = command_with_deadline(&command, &rfc3339_utc_millis(deadline_epoch_millis));
        let request_id = parse_command(command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingFailureResourceFetcher {
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("resource work must start before its absolute deadline");
        let remaining_millis = deadline_epoch_millis
            .saturating_sub(now_epoch_millis())
            .saturating_add(100);
        if remaining_millis > 0 {
            thread::sleep(Duration::from_millis(remaining_millis as u64));
        }
        release_sender.send(()).unwrap();

        let terminal = read_frame(&mut client, 4096).unwrap();
        let terminal_payload: Value = serde_json::from_slice(&terminal.payload).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(terminal_payload["code"], "RENDER_DEADLINE_EXCEEDED");
        assert_eq!(terminal_payload["requestId"], request_id);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_expired_command_is_terminal_before_queue_capacity_admission() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-expired-admission-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, bodies) = renderable_resource_fixture();
        let base_command = command_with_document(&document);
        let commands = (0..5)
            .map(|index| {
                command_with_request_id(
                    &base_command,
                    &format!("123e4567-e89b-42d3-a456-42661417404{index}"),
                )
            })
            .collect::<Vec<_>>();
        let expired_command = command_with_deadline(
            &command_with_request_id(&base_command, "123e4567-e89b-42d3-a456-426614174045"),
            "2000-01-01T00:00:00.000Z",
        );
        let expired_request_id = parse_command(expired_command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingResourceFetcher {
            bodies,
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, commands[0].as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("the active request must occupy the execution slot");
        for command in &commands[1..] {
            write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        }
        write_frame(&mut client, FrameType::Command, expired_command.as_bytes()).unwrap();

        let expired = read_frame(&mut client, 4096).unwrap();
        let expired_payload: Value = serde_json::from_slice(&expired.payload).unwrap();
        release_sender.send(()).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(expired_payload["code"], "RENDER_DEADLINE_EXCEEDED");
        assert_eq!(expired_payload["engineStage"], "REQUEST_CONTROL");
        assert_eq!(expired_payload["requestId"], expired_request_id);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_active_same_digest_joins_drift_conflicts_and_terminal_replays() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-join-replay-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, bodies) = renderable_resource_fixture();
        let command = command_with_document(&document);
        let drifted_command = command.replace(r#""dpi":96"#, r#""dpi":97"#);
        let request_id = parse_command(command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingResourceFetcher {
            bodies,
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("resource work must remain active for join");
        write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        write_frame(&mut client, FrameType::Command, drifted_command.as_bytes()).unwrap();
        let conflict = read_frame(&mut client, 4096).unwrap();
        let conflict_payload: Value = serde_json::from_slice(&conflict.payload).unwrap();
        release_sender.send(()).unwrap();
        let terminal = read_frame(&mut client, 4096).unwrap();
        write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        let replay = read_frame(&mut client, 4096).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(conflict_payload["code"], "RENDER_REQUEST_CONFLICT");
        assert_eq!(conflict_payload["requestId"], request_id);
        assert_eq!(terminal.frame_type, FrameType::Problem);
        assert_eq!(replay.frame_type, terminal.frame_type);
        assert_eq!(replay.payload, terminal.payload);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_uds_active_cancel_after_deadline_seals_deadline_and_discards_work() {
        use std::net::Shutdown;
        use std::os::unix::net::{UnixListener, UnixStream};
        use std::thread;

        let identity = validate_process_manifest(PROCESS_MANIFEST).unwrap();
        let socket = std::env::temp_dir().join(format!(
            "renderweave-t123-cancel-after-deadline-{}-{}.sock",
            std::process::id(),
            now_epoch_millis()
        ));
        assert!(!socket.exists());
        let listener = UnixListener::bind(&socket).unwrap();
        let (document, _) = renderable_resource_fixture();
        let deadline_epoch_millis = now_epoch_millis().saturating_add(3_000);
        let command = command_with_deadline(
            &command_with_document(&document),
            &rfc3339_utc_millis(deadline_epoch_millis),
        );
        let cancel = cancel_for_command(&command);
        let request_id = parse_command(command.as_bytes())
            .unwrap()
            .command
            .request_id;
        let (started_sender, started_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let fetcher = Arc::new(BlockingFailureResourceFetcher {
            started_sender: Mutex::new(Some(started_sender)),
            release_receiver: Mutex::new(Some(release_receiver)),
        });
        let server_identity = identity.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let registry = concurrent_request_registry_with(fetcher);
            let _ = serve_unix_connection(stream, &server_identity, &registry, 4096);
        });

        let mut client = UnixStream::connect(&socket).unwrap();
        write_frame(
            &mut client,
            FrameType::ClientHello,
            &client_hello_bytes(&identity.manifest_sha256).unwrap(),
        )
        .unwrap();
        assert_eq!(
            read_frame(&mut client, 4096).unwrap().frame_type,
            FrameType::ServerHello
        );
        write_frame(&mut client, FrameType::Command, command.as_bytes()).unwrap();
        started_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("resource work must start before its absolute deadline");
        let remaining_millis = deadline_epoch_millis
            .saturating_sub(now_epoch_millis())
            .saturating_add(100);
        if remaining_millis > 0 {
            thread::sleep(Duration::from_millis(remaining_millis as u64));
        }
        write_frame(&mut client, FrameType::Cancel, &cancel).unwrap();
        let terminal = read_frame(&mut client, 4096).unwrap();
        let terminal_payload: Value = serde_json::from_slice(&terminal.payload).unwrap();
        release_sender.send(()).unwrap();
        let _ = client.shutdown(Shutdown::Both);
        server.join().unwrap();
        std::fs::remove_file(&socket).unwrap();

        assert_eq!(terminal_payload["code"], "RENDER_DEADLINE_EXCEEDED");
        assert_eq!(terminal_payload["engineStage"], "REQUEST_CONTROL");
        assert_eq!(terminal_payload["requestId"], request_id);
    }

    fn vector_json(id: &str) -> String {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == id)
            .unwrap()["canonicalJson"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    fn vector_document(id: &str) -> String {
        vector_value(id, "documentCanonicalJson")
    }

    fn vector_value(id: &str, member: &str) -> String {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == id)
            .unwrap()[member]
            .as_str()
            .unwrap()
            .to_owned()
    }

    fn all_kinds_with_expiries(expires_at_epoch_seconds: [u64; 2]) -> String {
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        let resources = document["resources"].as_array_mut().unwrap();
        assert_eq!(resources.len(), expires_at_epoch_seconds.len());
        for (index, (resource, expires_at)) in resources
            .iter_mut()
            .zip(expires_at_epoch_seconds)
            .enumerate()
        {
            resource["expiresAt"] = Value::from(expires_at);
            resource["fetchUrl"] = Value::String(format!(
                "{ASSET_FETCH_ORIGIN}/internal/render-assets/token-{index}"
            ));
        }
        serde_json::to_string(&document).unwrap()
    }

    fn request_registry() -> RequestRegistry {
        request_registry_with(Arc::new(SuccessResourceFetcher))
    }

    fn request_registry_with(resource_fetcher: Arc<dyn ResourceFetcher>) -> RequestRegistry {
        RequestRegistry::new(
            renderweave_renderer_resource::FetchTargetPolicy::new(
                ASSET_FETCH_ORIGIN,
                renderweave_renderer_resource::ASSET_FETCH_PATH_PREFIX,
            )
            .unwrap(),
            resource_fetcher,
        )
    }

    #[cfg(target_os = "linux")]
    fn concurrent_request_registry_with(
        resource_fetcher: Arc<dyn ResourceFetcher>,
    ) -> ConcurrentRequestRegistry {
        ConcurrentRequestRegistry::new(
            renderweave_renderer_resource::FetchTargetPolicy::new(
                ASSET_FETCH_ORIGIN,
                renderweave_renderer_resource::ASSET_FETCH_PATH_PREFIX,
            )
            .unwrap(),
            resource_fetcher,
        )
    }

    struct SuccessResourceFetcher;

    impl ResourceFetcher for SuccessResourceFetcher {
        fn fetch_resource(
            &self,
            _target: &renderweave_renderer_resource::AdmittedFetchTarget<'_>,
            _deadline_epoch_millis: i64,
            _state: &mut renderweave_renderer_resource::RequestResourceFetchState,
            _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
        ) -> Result<FetchedResource, ResourceFetchProblem> {
            panic!("the fixture command must not fetch a resource")
        }
    }

    struct RecordingResourceFetcher {
        observations: Arc<Mutex<Vec<String>>>,
        bodies: BTreeMap<String, Vec<u8>>,
        fail_first: bool,
    }

    struct CancellingResourceFetcher {
        cancellation: Arc<std::sync::atomic::AtomicBool>,
        bodies: BTreeMap<String, Vec<u8>>,
    }

    #[cfg(target_os = "linux")]
    struct BlockingResourceFetcher {
        bodies: BTreeMap<String, Vec<u8>>,
        started_sender: Mutex<Option<mpsc::Sender<()>>>,
        release_receiver: Mutex<Option<mpsc::Receiver<()>>>,
    }

    #[cfg(target_os = "linux")]
    struct BlockingFailureResourceFetcher {
        started_sender: Mutex<Option<mpsc::Sender<()>>>,
        release_receiver: Mutex<Option<mpsc::Receiver<()>>>,
    }

    #[cfg(target_os = "linux")]
    impl ResourceFetcher for BlockingFailureResourceFetcher {
        fn fetch_resource(
            &self,
            target: &renderweave_renderer_resource::AdmittedFetchTarget<'_>,
            _deadline_epoch_millis: i64,
            _state: &mut renderweave_renderer_resource::RequestResourceFetchState,
            _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
        ) -> Result<FetchedResource, ResourceFetchProblem> {
            self.started_sender
                .lock()
                .unwrap()
                .take()
                .expect("blocking failure sender must exist")
                .send(())
                .unwrap();
            self.release_receiver
                .lock()
                .unwrap()
                .take()
                .expect("blocking failure release receiver must exist")
                .recv_timeout(Duration::from_secs(15))
                .expect("test must release the failed resource fetch");
            Err(ResourceFetchProblem::fetch_failed(target.resource_id()))
        }
    }

    #[cfg(target_os = "linux")]
    impl ResourceFetcher for BlockingResourceFetcher {
        fn fetch_resource(
            &self,
            target: &renderweave_renderer_resource::AdmittedFetchTarget<'_>,
            _deadline_epoch_millis: i64,
            state: &mut renderweave_renderer_resource::RequestResourceFetchState,
            _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
        ) -> Result<FetchedResource, ResourceFetchProblem> {
            if let Some(started_sender) = self.started_sender.lock().unwrap().take() {
                started_sender.send(()).unwrap();
                self.release_receiver
                    .lock()
                    .unwrap()
                    .take()
                    .expect("release receiver must exist for the first fetch")
                    .recv_timeout(Duration::from_secs(15))
                    .expect("test must release the blocked resource fetch");
            }
            state.verify_owned_body(
                target,
                self.bodies
                    .get(target.resource_id())
                    .expect("fixture body must exist")
                    .clone()
                    .into_boxed_slice(),
            )
        }
    }

    impl ResourceFetcher for RecordingResourceFetcher {
        fn fetch_resource(
            &self,
            target: &renderweave_renderer_resource::AdmittedFetchTarget<'_>,
            _deadline_epoch_millis: i64,
            state: &mut renderweave_renderer_resource::RequestResourceFetchState,
            _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
        ) -> Result<FetchedResource, ResourceFetchProblem> {
            let mut observations = self.observations.lock().unwrap();
            let is_first = observations.is_empty();
            observations.push(target.resource_id().to_owned());
            drop(observations);
            if self.fail_first && is_first {
                return Err(ResourceFetchProblem::fetch_failed(target.resource_id()));
            }
            state.verify_owned_body(
                target,
                self.bodies
                    .get(target.resource_id())
                    .expect("fixture body must exist")
                    .clone()
                    .into_boxed_slice(),
            )
        }
    }

    impl ResourceFetcher for CancellingResourceFetcher {
        fn fetch_resource(
            &self,
            target: &renderweave_renderer_resource::AdmittedFetchTarget<'_>,
            _deadline_epoch_millis: i64,
            state: &mut renderweave_renderer_resource::RequestResourceFetchState,
            _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
        ) -> Result<FetchedResource, ResourceFetchProblem> {
            let body = self
                .bodies
                .get(target.resource_id())
                .expect("fixture body must exist")
                .clone()
                .into_boxed_slice();
            self.cancellation
                .store(true, std::sync::atomic::Ordering::SeqCst);
            state.verify_owned_body(target, body)
        }
    }

    fn renderable_resource_fixture() -> (String, BTreeMap<String, Vec<u8>>) {
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        let mut text = document["canvas"]["children"][4].clone();
        let mut image = document["canvas"]["children"][5].clone();
        text["occurrenceId"] = Value::String("rwocc_0000000000000001".to_owned());
        image["occurrenceId"] = Value::String("rwocc_0000000000000002".to_owned());
        document["canvas"]["children"] = serde_json::json!([text, image]);
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let font_case = asset_case(&asset_vectors, "font-ttf-admitted");
        let image_case = asset_case(&asset_vectors, "png-rgba-admitted");
        let font_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let image_id = "rwres_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        document["resources"] = serde_json::json!([
            render_resource(
                font_case,
                font_id,
                &format!("{ASSET_FETCH_ORIGIN}/internal/render-assets/token-0")
            ),
            render_resource(
                image_case,
                image_id,
                &format!("{ASSET_FETCH_ORIGIN}/internal/render-assets/token-1")
            )
        ]);
        let document = serde_json::to_string(&document).unwrap();
        let admitted = validate_render_document(&document).unwrap();
        assert!(preflight_layout(&admitted).is_ok());
        let bodies = BTreeMap::from([
            (font_id.to_owned(), asset_body(font_case)),
            (image_id.to_owned(), asset_body(image_case)),
        ]);
        (document, bodies)
    }

    fn media_mismatch_resource_fixture() -> (String, BTreeMap<String, Vec<u8>>) {
        let (document, mut bodies) = renderable_resource_fixture();
        let mut document: Value = serde_json::from_str(&document).unwrap();
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let image_case = asset_case(&asset_vectors, "png-rgba-admitted");
        let image_expected = &image_case["expected"];
        let first_id = "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        document["resources"][0]["byteLength"] = image_expected["byteLength"].clone();
        document["resources"][0]["sha256"] = Value::String(format!(
            "sha256:{}",
            image_expected["sha256"].as_str().unwrap()
        ));
        bodies.insert(first_id.to_owned(), asset_body(image_case));
        let document = serde_json::to_string(&document).unwrap();
        let admitted = validate_render_document(&document).unwrap();
        assert!(preflight_layout(&admitted).is_ok());
        (document, bodies)
    }

    fn asset_case<'a>(vectors: &'a Value, id: &str) -> &'a Value {
        vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == id)
            .unwrap()
    }

    fn asset_body(case: &Value) -> Vec<u8> {
        decode_base64(case["input"]["data"].as_str().unwrap())
    }

    fn render_resource(case: &Value, resource_id: &str, fetch_url: &str) -> Value {
        let expected = &case["expected"];
        let mut descriptor = expected["descriptor"].as_object().unwrap().clone();
        let descriptor_kind = descriptor.remove("type").unwrap();
        descriptor.insert(
            "kind".to_owned(),
            Value::String(descriptor_kind.as_str().unwrap().to_ascii_lowercase()),
        );
        let media_type = if case["id"].as_str().unwrap().starts_with("font-ttf-") {
            "font/ttf"
        } else {
            "image/png"
        };
        serde_json::json!({
            "acceptanceProfileId": "renderweave-asset-acceptance/1.0",
            "byteLength": expected["byteLength"],
            "expiresAt": 4_090_912_502_u64,
            "fetchUrl": fetch_url,
            "kind": expected["kind"].as_str().unwrap().to_ascii_lowercase(),
            "mediaType": media_type,
            "resourceId": resource_id,
            "sha256": format!("sha256:{}", expected["sha256"].as_str().unwrap()),
            "technicalDescriptor": Value::Object(descriptor)
        })
    }

    fn decode_base64(value: &str) -> Vec<u8> {
        let mut output = Vec::new();
        let mut accumulator = 0_u32;
        let mut bits = 0_u8;
        for byte in value.bytes() {
            if byte == b'=' {
                break;
            }
            let value = match byte {
                b'A'..=b'Z' => byte - b'A',
                b'a'..=b'z' => byte - b'a' + 26,
                b'0'..=b'9' => byte - b'0' + 52,
                b'+' => 62,
                b'/' => 63,
                _ => panic!("invalid base64 fixture"),
            };
            accumulator = (accumulator << 6) | u32::from(value);
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                output.push((accumulator >> bits) as u8);
                accumulator &= (1_u32 << bits) - 1;
            }
        }
        output
    }

    fn command_with_document(document: &str) -> String {
        let command = vector_json("png-command");
        let original_document = vector_document("png-command");
        let old_digest = vector_value("png-command", "renderDocumentDigest");
        let digest = renderweave_renderer_protocol::digest_with_domain(
            renderweave_renderer_protocol::DOCUMENT_DIGEST_DOMAIN,
            document.as_bytes(),
        );
        command
            .replace(&original_document, document)
            .replace(&old_digest, &digest)
    }

    #[cfg(target_os = "linux")]
    fn command_with_request_id(command: &str, request_id: &str) -> String {
        let admitted = parse_command(command.as_bytes()).unwrap();
        command.replace(&admitted.command.request_id, request_id)
    }

    #[cfg(target_os = "linux")]
    fn command_with_deadline(command: &str, deadline_at: &str) -> String {
        let admitted = parse_command(command.as_bytes()).unwrap();
        command.replace(&admitted.command.deadline_at, deadline_at)
    }

    #[cfg(target_os = "linux")]
    fn rfc3339_utc_millis(epoch_millis: i64) -> String {
        let epoch_seconds = epoch_millis.div_euclid(1_000);
        let millis = epoch_millis.rem_euclid(1_000);
        let days = epoch_seconds.div_euclid(86_400);
        let seconds_of_day = epoch_seconds.rem_euclid(86_400);
        let shifted_days = days + 719_468;
        let era = if shifted_days >= 0 {
            shifted_days
        } else {
            shifted_days - 146_096
        } / 146_097;
        let day_of_era = shifted_days - era * 146_097;
        let year_of_era =
            (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
        let mut year = year_of_era + era * 400;
        let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
        let month_prime = (5 * day_of_year + 2) / 153;
        let day = day_of_year - (153 * month_prime + 2) / 5 + 1;
        let month = month_prime + if month_prime < 10 { 3 } else { -9 };
        year += i64::from(month <= 2);
        let hour = seconds_of_day / 3_600;
        let minute = seconds_of_day % 3_600 / 60;
        let second = seconds_of_day % 60;
        format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}.{millis:03}Z")
    }

    #[cfg(target_os = "linux")]
    fn cancel_for_command(command: &str) -> Vec<u8> {
        let admitted = parse_command(command.as_bytes()).unwrap();
        serde_json::to_vec(&renderweave_renderer_protocol::Cancel {
            contract_version: "renderweave-render-cancel/1.0".to_owned(),
            request_id: admitted.command.request_id,
            renderer_command_digest: admitted.command_digest,
            deadline_at: admitted.command.deadline_at,
        })
        .unwrap()
    }

    struct MemoryDuplex {
        input: Cursor<Vec<u8>>,
        output: Vec<u8>,
    }

    impl MemoryDuplex {
        fn new(input: Vec<u8>) -> Self {
            Self {
                input: Cursor::new(input),
                output: Vec::new(),
            }
        }
    }

    impl io::Read for MemoryDuplex {
        fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
            self.input.read(buffer)
        }
    }

    impl io::Write for MemoryDuplex {
        fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
            self.output.extend_from_slice(buffer);
            Ok(buffer.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }
}
