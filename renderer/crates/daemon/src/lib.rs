//! Constant Linux UDS daemon for the RenderWeave renderer process seam.
//!
//! The process intentionally has no registered raster profile. A structurally admitted command is
//! also passed through command-bound resource lease coverage and the static Layout Profile
//! preflight, then recorded in the in-memory registry with one stable terminal problem; no image,
//! scene, or partial result can be produced here.

#[cfg(any(unix, test))]
use renderweave_renderer_document::{validate_render_document, validate_resource_lease_coverage};
#[cfg(any(unix, test))]
use renderweave_renderer_layout::preflight_layout;
use renderweave_renderer_protocol::ProtocolError;
#[cfg(any(unix, test))]
use renderweave_renderer_protocol::{
    EngineStage, Frame, FrameType, ManifestIdentity, parse_cancel, parse_client_hello,
    parse_command, problem_bytes, read_frame, resource_problem_bytes, server_hello_bytes,
    validate_process_manifest, write_frame,
};
use renderweave_renderer_resource::{ASSET_FETCH_PATH_PREFIX, FetchTargetPolicy};
#[cfg(any(unix, test))]
use std::collections::BTreeMap;
use std::ffi::{OsStr, OsString};
use std::fmt::{Display, Formatter};
use std::io;
use std::path::{Path, PathBuf};
#[cfg(any(unix, test))]
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(any(unix, test))]
const TERMINAL_RETENTION_MILLIS: i64 = 5 * 60 * 1_000;
#[cfg(any(unix, test))]
const PRE_COMMAND_CANCEL_RETENTION_MILLIS: i64 = 60 * 1_000;

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

#[derive(Clone, Debug)]
pub struct DaemonConfiguration {
    socket_path: PathBuf,
    manifest_path: PathBuf,
    fetch_target_policy: FetchTargetPolicy,
    maximum_framed_bytes: usize,
}

impl DaemonConfiguration {
    pub fn new(
        socket_path: impl Into<PathBuf>,
        manifest_path: impl Into<PathBuf>,
        asset_fetch_origin: &str,
        maximum_framed_bytes: usize,
    ) -> Result<Self, DaemonError> {
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
        Ok(Self {
            socket_path,
            manifest_path,
            fetch_target_policy,
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
}

pub fn run_from_arguments(
    arguments: impl IntoIterator<Item = OsString>,
) -> Result<(), DaemonError> {
    let mut arguments = arguments.into_iter();
    let _program = arguments.next();
    let mut socket_path = None;
    let mut manifest_path = None;
    let mut asset_fetch_origin = None;
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
    let configuration = DaemonConfiguration::new(
        socket_path.ok_or(DaemonError::Configuration("--socket is required"))?,
        manifest_path.ok_or(DaemonError::Configuration("--manifest is required"))?,
        asset_fetch_origin,
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
    let mut registry = RequestRegistry::new(configuration.fetch_target_policy().clone());
    eprintln!("renderer daemon ready");
    for accepted in listener.incoming() {
        let mut connection = accepted?;
        if serve_connection(
            &mut connection,
            &identity,
            &mut registry,
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

#[cfg(any(unix, test))]
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
        let response = registry.handle(frame, now_epoch_millis())?;
        write_frame(connection, response.frame_type, &response.payload)?;
    }
}

#[cfg(any(unix, test))]
#[derive(Clone, Debug)]
struct RegistryEntry {
    command_digest: String,
    deadline_epoch_millis: i64,
    retain_until_epoch_millis: i64,
    problem_payload: Vec<u8>,
}

#[cfg(any(unix, test))]
struct RequestRegistry {
    entries: BTreeMap<String, RegistryEntry>,
    fetch_target_policy: FetchTargetPolicy,
}

#[cfg(any(unix, test))]
impl RequestRegistry {
    fn new(fetch_target_policy: FetchTargetPolicy) -> Self {
        Self {
            entries: BTreeMap::new(),
            fetch_target_policy,
        }
    }

    fn handle(&mut self, frame: Frame, now_epoch_millis: i64) -> Result<Frame, ProtocolError> {
        self.entries
            .retain(|_, entry| entry.retain_until_epoch_millis > now_epoch_millis);
        let payload = match frame.frame_type {
            FrameType::Command => self.handle_command(&frame.payload, now_epoch_millis)?,
            FrameType::Cancel => self.handle_cancel(&frame.payload, now_epoch_millis)?,
            _ => {
                return Err(ProtocolError::Invalid(
                    "post-handshake client frame must be COMMAND or CANCEL",
                ));
            }
        };
        Ok(Frame {
            frame_type: FrameType::Problem,
            payload,
        })
    }

    fn handle_command(
        &mut self,
        payload: &[u8],
        now_epoch_millis: i64,
    ) -> Result<Vec<u8>, ProtocolError> {
        let admitted = parse_command(payload)?;
        let request_id = admitted.command.request_id.clone();
        if let Some(existing) = self.entries.get(&request_id) {
            if existing.command_digest == admitted.command_digest
                && existing.deadline_epoch_millis == admitted.deadline_epoch_millis
            {
                return Ok(existing.problem_payload.clone());
            }
            return problem_bytes(
                &request_id,
                "RENDER_REQUEST_CONFLICT",
                EngineStage::RequestControl,
            );
        }

        let problem_payload = if admitted.deadline_epoch_millis <= now_epoch_millis {
            problem_bytes(
                &request_id,
                "RENDER_DEADLINE_EXCEEDED",
                EngineStage::RequestControl,
            )?
        } else {
            match validate_render_document(admitted.command.document.get()) {
                Err(_) => problem_bytes(
                    &request_id,
                    "RENDER_INTERNAL_ERROR",
                    EngineStage::DocumentAdmission,
                )?,
                Ok(document) => {
                    if let Err(violation) =
                        validate_resource_lease_coverage(&document, admitted.deadline_epoch_millis)
                    {
                        resource_problem_bytes(
                            &request_id,
                            "RESOURCE_LEASE_EXPIRED",
                            EngineStage::CommandAdmission,
                            violation.resource_id(),
                        )?
                    } else if document
                        .resources()
                        .iter()
                        .any(|resource| self.fetch_target_policy.admit(resource).is_err())
                    {
                        // A sealed URL outside the exact deployment target policy is an internal
                        // handoff violation. Never expose its URL, origin, path, token, or cause.
                        problem_bytes(
                            &request_id,
                            "RENDER_INTERNAL_ERROR",
                            EngineStage::ResourcePreparation,
                        )?
                    } else if preflight_layout(&document).is_err() {
                        // Static layout violations contradict the Java sealing authority. Until a
                        // Profile is registered, keep that invariant breach inside document
                        // admission and never expose internal property paths or a partial scene.
                        problem_bytes(
                            &request_id,
                            "RENDER_INTERNAL_ERROR",
                            EngineStage::DocumentAdmission,
                        )?
                    } else {
                        // The exact process manifest has no registered raster profile. This is a
                        // real, stable fail-closed terminal result, never a synthetic image
                        // implementation.
                        problem_bytes(
                            &request_id,
                            "RENDER_INTERNAL_ERROR",
                            EngineStage::CommandAdmission,
                        )?
                    }
                }
            }
        };
        self.entries.insert(
            request_id,
            RegistryEntry {
                command_digest: admitted.command_digest,
                deadline_epoch_millis: admitted.deadline_epoch_millis,
                retain_until_epoch_millis: admitted
                    .deadline_epoch_millis
                    .max(now_epoch_millis)
                    .saturating_add(TERMINAL_RETENTION_MILLIS),
                problem_payload: problem_payload.clone(),
            },
        );
        Ok(problem_payload)
    }

    fn handle_cancel(
        &mut self,
        payload: &[u8],
        now_epoch_millis: i64,
    ) -> Result<Vec<u8>, ProtocolError> {
        let cancel = parse_cancel(payload)?;
        if let Some(existing) = self.entries.get(&cancel.cancel.request_id) {
            if existing.command_digest == cancel.cancel.renderer_command_digest
                && existing.deadline_epoch_millis == cancel.deadline_epoch_millis
            {
                return Ok(existing.problem_payload.clone());
            }
            return problem_bytes(
                &cancel.cancel.request_id,
                "RENDER_REQUEST_CONFLICT",
                EngineStage::RequestControl,
            );
        }
        let deadline_epoch_millis = cancel.deadline_epoch_millis;
        let code = if deadline_epoch_millis <= now_epoch_millis {
            "RENDER_DEADLINE_EXCEEDED"
        } else {
            "RENDER_CANCELLED"
        };
        let problem_payload =
            problem_bytes(&cancel.cancel.request_id, code, EngineStage::RequestControl)?;
        self.entries.insert(
            cancel.cancel.request_id,
            RegistryEntry {
                command_digest: cancel.cancel.renderer_command_digest,
                deadline_epoch_millis,
                retain_until_epoch_millis: now_epoch_millis
                    .saturating_add(PRE_COMMAND_CANCEL_RETENTION_MILLIS),
                problem_payload: problem_payload.clone(),
            },
        );
        Ok(problem_payload)
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
    #[cfg(target_os = "linux")]
    use renderweave_renderer_protocol::client_hello_bytes;
    use renderweave_renderer_protocol::encode_frame;
    use serde_json::Value;
    use std::io::Cursor;

    const VECTORS: &str = include_str!("../../../protocol-vectors-v1.json");
    const PROCESS_MANIFEST: &[u8] = include_bytes!("../../../process-manifest.json");
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
    const ASSET_FETCH_ORIGIN: &str = "https://render.internal.example";

    #[test]
    fn daemon_requires_an_explicit_socket_manifest_fetch_origin_and_frame_limit() {
        assert!(DaemonConfiguration::new("", "", "", 0).is_err());
        assert!(DaemonConfiguration::new("/tmp/rw.sock", "", ASSET_FETCH_ORIGIN, 1).is_err());
        assert!(DaemonConfiguration::new("/tmp/rw.sock", "/tmp/manifest", "", 1).is_err());
        assert!(
            DaemonConfiguration::new(
                "/tmp/rw.sock",
                "/tmp/manifest",
                "http://render.internal.example",
                1
            )
            .is_err()
        );
        assert!(
            DaemonConfiguration::new("/tmp/rw.sock", "/tmp/manifest", ASSET_FETCH_ORIGIN, 0)
                .is_err()
        );
        assert!(
            DaemonConfiguration::new("/tmp/rw.sock", "/tmp/manifest", ASSET_FETCH_ORIGIN, 1)
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
        assert_eq!(first.frame_type, FrameType::Problem);
        assert_eq!(first.payload, vector_json("problem").as_bytes());

        let replay = registry
            .handle(
                Frame {
                    frame_type: FrameType::Command,
                    payload: command.as_bytes().to_vec(),
                },
                1_800_000_000_001,
            )
            .unwrap();
        assert_eq!(replay.payload, first.payload);

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
        let conflict: Value = serde_json::from_slice(&conflict.payload).unwrap();
        assert_eq!(conflict["code"], "RENDER_REQUEST_CONFLICT");
        assert_eq!(conflict["engineStage"], "REQUEST_CONTROL");
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
        let problem: Value = serde_json::from_slice(&response.payload).unwrap();
        assert_eq!(problem["code"], "RENDER_INTERNAL_ERROR");
        assert_eq!(problem["engineStage"], "DOCUMENT_ADMISSION");
    }

    #[test]
    fn registry_rejects_admitted_but_layout_invalid_document_before_profile_lookup() {
        let layout_invalid_document = all_kinds_with_expiries([4_090_912_502; 2]);
        let admitted = validate_render_document(&layout_invalid_document).unwrap();
        assert!(preflight_layout(&admitted).is_err());
        let invalid_command = command_with_document(&layout_invalid_document);

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
        let problem: Value = serde_json::from_slice(&response.payload).unwrap();
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
        let problem: Value = serde_json::from_slice(&first.payload).unwrap();
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
        assert_eq!(replay.payload, first.payload);
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
        let problem: Value = serde_json::from_slice(&response.payload).unwrap();
        assert_eq!(problem["code"], "RENDER_INTERNAL_ERROR");
        assert_eq!(problem["engineStage"], "RESOURCE_PREPARATION");
        assert!(problem.get("resourceId").is_none());
        assert_eq!(problem["parameters"], serde_json::json!({}));
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
        assert_eq!(response.frame_type, FrameType::Problem);
        let problem: Value = serde_json::from_slice(&response.payload).unwrap();
        assert_eq!(problem["code"], "RENDER_CANCELLED");
        assert_eq!(problem["engineStage"], "REQUEST_CONTROL");
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
            let (mut stream, _) = listener.accept().unwrap();
            let mut registry = request_registry();
            serve_connection(&mut stream, &server_identity, &mut registry, 4096).unwrap();
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
        RequestRegistry::new(
            renderweave_renderer_resource::FetchTargetPolicy::new(
                ASSET_FETCH_ORIGIN,
                renderweave_renderer_resource::ASSET_FETCH_PATH_PREFIX,
            )
            .unwrap(),
        )
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
