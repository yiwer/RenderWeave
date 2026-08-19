package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Asset HTTP surface. Registered only when the Asset application is assembled (S3 endpoint
 * configured); otherwise the surface fails closed and the routes do not exist.
 */
@RestController
@ConditionalOnExpression("'${renderweave.asset.s3.endpoint:}' != ''")
@RequestMapping("/api/v1/assets")
final class AssetController {

    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_SCALARS = 64;
    private static final int MAX_DISPLAY_NAME_SCALARS = 200;
    private static final int MAX_SOURCE_FILE_NAME_SCALARS = 255;
    private static final int MAX_CURSOR_CHARACTERS = 512;
    private static final int MAX_CATALOG_LIMIT = 100;

    private final AssetApplication assets;
    private final ObjectMapper json;

    AssetController(AssetApplication assets, ObjectMapper json) {
        this.assets = assets;
        this.json = json;
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam("kind") String kind,
            @RequestParam("displayName") String displayName,
            @RequestParam(name = "tags", required = false) List<String> tags,
            @RequestParam(name = "sourceFileName", required = false) String sourceFileName,
            @RequestPart("content") MultipartFile content
    ) {
        requireIdempotencyKey(idempotencyKey);
        var assetKind = requireKind(kind);
        requireDisplayName(displayName);
        var normalizedTags = requireTags(tags);
        requireSourceFileName(sourceFileName);
        if (content == null || content.isEmpty()) {
            throw new InvalidAssetApiRequestException("content part must carry non-empty bytes");
        }
        final byte[] rawContent;
        try {
            rawContent = content.getBytes();
        } catch (IOException failure) {
            throw new InvalidAssetApiRequestException("Uploaded content could not be read", failure);
        }
        var outcome = assets.create(
                invocation(),
                new AssetApplication.CreateCommand(
                        idempotencyKey,
                        assetKind,
                        displayName,
                        normalizedTags,
                        sourceFileName,
                        rawContent
                )
        );
        return switch (outcome) {
            case AssetApplication.CreatedReadable created -> ResponseEntity
                    .created(assetUri(created.detail().assetId()))
                    .body(readable(created.detail()));
            case AssetApplication.CreatedOpaque created -> ResponseEntity
                    .created(assetUri(created.assetId()))
                    .body(new OpaqueAssetResponse(created.assetId().value(), "OPAQUE"));
            case AssetApplication.CreateContentRejected rejected ->
                    contentProblem(rejected.rejection());
            case AssetApplication.CreateForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset creation is not permitted"
            );
            case AssetApplication.CreateIdempotencyConflict ignored -> problem(
                    HttpStatus.CONFLICT,
                    "ASSET_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for a different Asset request"
            );
            case AssetApplication.CreateStorageCapacityExceeded ignored -> problem(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "ASSET_STORAGE_CAPACITY_EXCEEDED",
                    "The deployment-level Asset capacity watermark would be exceeded"
            );
            case AssetApplication.CreateAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.CreatePersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> catalog(
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "tagsAll", required = false) List<String> tagsAll,
            @RequestParam(name = "tagsAny", required = false) List<String> tagsAny,
            @RequestParam(name = "displayName", required = false) String displayName,
            @RequestParam(name = "sourceFileName", required = false) String sourceFileName,
            @RequestParam(name = "includeDeleted", required = false, defaultValue = "false")
            boolean includeDeleted,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (limit < 1 || limit > MAX_CATALOG_LIMIT) {
            throw new InvalidAssetApiRequestException(
                    "limit must be between 1 and " + MAX_CATALOG_LIMIT
            );
        }
        requireFilterTags(tagsAll);
        requireFilterTags(tagsAny);
        if (displayName != null && displayName.length() > MAX_DISPLAY_NAME_SCALARS) {
            throw new InvalidAssetApiRequestException(
                    "displayName must be at most " + MAX_DISPLAY_NAME_SCALARS + " characters"
            );
        }
        if (sourceFileName != null && sourceFileName.length() > MAX_SOURCE_FILE_NAME_SCALARS) {
            throw new InvalidAssetApiRequestException(
                    "sourceFileName must be at most " + MAX_SOURCE_FILE_NAME_SCALARS + " characters"
            );
        }
        if (cursor != null && cursor.length() > MAX_CURSOR_CHARACTERS) {
            throw new InvalidAssetApiRequestException(
                    "cursor must be at most " + MAX_CURSOR_CHARACTERS + " characters"
            );
        }
        var outcome = assets.catalog(
                invocation(),
                new AssetApplication.CatalogCommand(
                        kind == null ? null : requireKind(kind),
                        tagsAll,
                        tagsAny,
                        displayName,
                        sourceFileName,
                        includeDeleted,
                        cursor,
                        limit
                )
        );
        return switch (outcome) {
            case AssetApplication.CatalogPage page -> ResponseEntity.ok(
                    new AssetCatalogResponse(
                            page.entries().stream().map(AssetController::catalogEntry).toList(),
                            page.nextCursor().orElse(null)
                    )
            );
            case AssetApplication.CatalogForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset catalog access is not permitted"
            );
            case AssetApplication.CatalogAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.CatalogPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @GetMapping(value = "/{assetId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> getCurrent(@PathVariable String assetId) {
        var outcome = assets.getCurrent(invocation(), assetId(assetId));
        return switch (outcome) {
            case AssetApplication.CurrentReadable current -> ResponseEntity.ok(
                    readable(current.detail())
            );
            case AssetApplication.CurrentNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_NOT_FOUND",
                    "Asset was not found"
            );
            case AssetApplication.CurrentDeleted ignored -> problem(
                    HttpStatus.GONE,
                    "ASSET_DELETED",
                    "Asset is deleted"
            );
            case AssetApplication.CurrentForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset read is not permitted"
            );
            case AssetApplication.CurrentAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.CurrentPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @PutMapping(
            value = "/{assetId}/metadata",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> updateMetadata(
            @PathVariable String assetId,
            @RequestParam long expectedAssetRevision,
            @RequestBody UpdateAssetMetadataRequest body
    ) {
        if (expectedAssetRevision < 0 || expectedAssetRevision == Long.MAX_VALUE) {
            throw new InvalidAssetApiRequestException(
                    "expectedAssetRevision must be non-negative and have a successor"
            );
        }
        if (body == null) {
            throw new InvalidAssetApiRequestException("metadata body is required");
        }
        requireDisplayName(body.displayName());
        var normalizedTags = requireTags(body.tags());
        var outcome = assets.updateMetadata(
                invocation(),
                new AssetApplication.UpdateMetadataCommand(
                        assetId(assetId),
                        expectedAssetRevision,
                        body.displayName(),
                        normalizedTags
                )
        );
        return switch (outcome) {
            case AssetApplication.UpdatedReadable updated -> ResponseEntity.ok(
                    readable(updated.detail())
            );
            case AssetApplication.UpdateNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_NOT_FOUND",
                    "Asset was not found"
            );
            case AssetApplication.UpdateDeleted ignored -> problem(
                    HttpStatus.CONFLICT,
                    "ASSET_DELETED",
                    "Deleted Asset metadata cannot be updated"
            );
            case AssetApplication.UpdateForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset update is not permitted"
            );
            case AssetApplication.UpdateRevisionConflict conflict ->
                    conflictProblem(conflict.currentAssetRevision());
            case AssetApplication.UpdateAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.UpdatePersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @GetMapping(value = "/{assetId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> listContentVersions(@PathVariable String assetId) {
        var outcome = assets.listContentVersions(invocation(), assetId(assetId));
        return switch (outcome) {
            case AssetApplication.VersionsReadable versions -> ResponseEntity.ok(
                    new AssetVersionsResponse(
                            versions.entries().stream().map(AssetController::versionEntry).toList()
                    )
            );
            case AssetApplication.VersionsNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_NOT_FOUND",
                    "Asset was not found"
            );
            case AssetApplication.VersionsDeleted ignored -> problem(
                    HttpStatus.GONE,
                    "ASSET_DELETED",
                    "Asset is deleted"
            );
            case AssetApplication.VersionsForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset read is not permitted"
            );
            case AssetApplication.VersionsAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.VersionsPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @GetMapping(value = "/{assetId}/download")
    ResponseEntity<?> downloadExact(
            @PathVariable String assetId,
            @RequestParam long contentVersion
    ) {
        if (contentVersion < 0) {
            throw new InvalidAssetApiRequestException("contentVersion must be non-negative");
        }
        var outcome = assets.downloadExact(invocation(), assetId(assetId), contentVersion);
        return switch (outcome) {
            case AssetApplication.DownloadReadable downloaded -> downloaded(downloaded.content());
            case AssetApplication.DownloadNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_NOT_FOUND",
                    "Asset was not found"
            );
            case AssetApplication.DownloadDeleted ignored -> problem(
                    HttpStatus.GONE,
                    "ASSET_DELETED",
                    "Asset is deleted"
            );
            case AssetApplication.DownloadForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset read is not permitted"
            );
            case AssetApplication.DownloadVersionNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_CONTENT_VERSION_NOT_FOUND",
                    "The exact content version does not exist"
            );
            case AssetApplication.DownloadBlobUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_BLOB_UNAVAILABLE",
                    "The stored Asset content could not be loaded or verified"
            );
            case AssetApplication.DownloadAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.DownloadPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @GetMapping(value = "/{assetId}/preview")
    ResponseEntity<?> preview(@PathVariable String assetId) {
        var id = assetId(assetId);
        var current = assets.getCurrent(invocation(), id);
        return switch (current) {
            case AssetApplication.CurrentReadable readable -> {
                var download = assets.downloadExact(
                        invocation(),
                        id,
                        readable.detail().currentContentVersion()
                );
                yield switch (download) {
                    case AssetApplication.DownloadReadable downloaded ->
                            downloaded(downloaded.content());
                    case AssetApplication.DownloadNotFound ignored -> problem(
                            HttpStatus.NOT_FOUND,
                            "ASSET_NOT_FOUND",
                            "Asset was not found"
                    );
                    case AssetApplication.DownloadDeleted ignored -> problem(
                            HttpStatus.GONE,
                            "ASSET_DELETED",
                            "Asset is deleted"
                    );
                    case AssetApplication.DownloadForbidden ignored -> problem(
                            HttpStatus.FORBIDDEN,
                            "ASSET_FORBIDDEN",
                            "Asset read is not permitted"
                    );
                    case AssetApplication.DownloadVersionNotFound ignored -> problem(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "ASSET_CONTENT_VERSION_MISSING",
                            "The current content version could not be located"
                    );
                    case AssetApplication.DownloadBlobUnavailable ignored -> problem(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "ASSET_BLOB_UNAVAILABLE",
                            "The stored Asset content could not be loaded or verified"
                    );
                    case AssetApplication.DownloadAuthorityUnavailable ignored -> problem(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "ASSET_AUTHORITY_UNAVAILABLE",
                            "Asset authorization is unavailable"
                    );
                    case AssetApplication.DownloadPersistenceUnavailable ignored -> problem(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "ASSET_PERSISTENCE_UNAVAILABLE",
                            "Asset persistence is unavailable"
                    );
                };
            }
            case AssetApplication.CurrentNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_NOT_FOUND",
                    "Asset was not found"
            );
            case AssetApplication.CurrentDeleted ignored -> problem(
                    HttpStatus.GONE,
                    "ASSET_DELETED",
                    "Asset is deleted"
            );
            case AssetApplication.CurrentForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset read is not permitted"
            );
            case AssetApplication.CurrentAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.CurrentPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @PutMapping(
            value = "/{assetId}/content",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> replaceContent(
            @PathVariable String assetId,
            @RequestParam long expectedAssetRevision,
            @RequestBody byte[] rawContent
    ) {
        if (expectedAssetRevision < 0 || expectedAssetRevision == Long.MAX_VALUE) {
            throw new InvalidAssetApiRequestException(
                    "expectedAssetRevision must be non-negative and have a successor"
            );
        }
        if (rawContent == null || rawContent.length == 0) {
            throw new InvalidAssetApiRequestException("content body must carry non-empty bytes");
        }
        var outcome = assets.replaceContent(
                invocation(),
                new AssetApplication.ReplaceContentCommand(
                        assetId(assetId),
                        expectedAssetRevision,
                        rawContent
                )
        );
        return switch (outcome) {
            case AssetApplication.ReplaceApplied applied -> ResponseEntity.ok(
                    readable(applied.detail())
            );
            case AssetApplication.ReplaceNoOp noOp -> ResponseEntity.ok(
                    readable(noOp.detail())
            );
            case AssetApplication.ReplaceContentRejected rejected ->
                    contentProblem(rejected.rejection());
            case AssetApplication.ReplaceNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_NOT_FOUND",
                    "Asset was not found"
            );
            case AssetApplication.ReplaceDeleted ignored -> problem(
                    HttpStatus.CONFLICT,
                    "ASSET_DELETED",
                    "Deleted Asset content cannot be replaced"
            );
            case AssetApplication.ReplaceForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset update is not permitted"
            );
            case AssetApplication.ReplaceRevisionConflict conflict ->
                    conflictProblem(conflict.currentAssetRevision());
            case AssetApplication.ReplaceStorageCapacityExceeded ignored -> problem(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "ASSET_STORAGE_CAPACITY_EXCEEDED",
                    "The deployment-level Asset capacity watermark would be exceeded"
            );
            case AssetApplication.ReplaceAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.ReplacePersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    @PostMapping(value = "/{assetId}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> restoreContent(
            @PathVariable String assetId,
            @RequestParam long expectedAssetRevision,
            @RequestParam long sourceContentVersion
    ) {
        if (expectedAssetRevision < 0 || expectedAssetRevision == Long.MAX_VALUE) {
            throw new InvalidAssetApiRequestException(
                    "expectedAssetRevision must be non-negative and have a successor"
            );
        }
        if (sourceContentVersion < 0) {
            throw new InvalidAssetApiRequestException("sourceContentVersion must be non-negative");
        }
        var outcome = assets.restoreContent(
                invocation(),
                new AssetApplication.RestoreContentCommand(
                        assetId(assetId),
                        expectedAssetRevision,
                        sourceContentVersion
                )
        );
        return switch (outcome) {
            case AssetApplication.RestoreApplied applied -> ResponseEntity.ok(
                    readable(applied.detail())
            );
            case AssetApplication.RestoreNoOp noOp -> ResponseEntity.ok(
                    readable(noOp.detail())
            );
            case AssetApplication.RestoreNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_NOT_FOUND",
                    "Asset was not found"
            );
            case AssetApplication.RestoreDeleted ignored -> problem(
                    HttpStatus.CONFLICT,
                    "ASSET_DELETED",
                    "Deleted Asset content cannot be restored"
            );
            case AssetApplication.RestoreForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "ASSET_FORBIDDEN",
                    "Asset update is not permitted"
            );
            case AssetApplication.RestoreRevisionConflict conflict ->
                    conflictProblem(conflict.currentAssetRevision());
            case AssetApplication.RestoreVersionNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "ASSET_CONTENT_VERSION_NOT_FOUND",
                    "The exact source content version does not exist"
            );
            case AssetApplication.RestoreAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_AUTHORITY_UNAVAILABLE",
                    "Asset authorization is unavailable"
            );
            case AssetApplication.RestorePersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ASSET_PERSISTENCE_UNAVAILABLE",
                    "Asset persistence is unavailable"
            );
        };
    }

    private AssetResponse readable(AssetApplication.AssetDetail detail) {
        return new AssetResponse(
                detail.assetId().value(),
                "READABLE",
                detail.kind().name(),
                detail.lifecycle().name(),
                detail.assetRevision(),
                detail.currentContentVersion(),
                detail.displayName(),
                detail.tags(),
                detail.sourceFileName(),
                detail.mediaType(),
                detail.byteLength(),
                detail.sha256(),
                descriptor(detail.descriptor()),
                detail.createdAt().toString(),
                detail.updatedAt().toString()
        );
    }

    private JsonNode descriptor(AssetAcceptanceAuthority.TechnicalDescriptor descriptor) {
        return json.valueToTree(descriptor);
    }

    private ResponseEntity<AssetProblemResponse> contentProblem(
            AssetAcceptanceAuthority.Rejected rejection
    ) {
        var status = rejection.code() == AssetAcceptanceAuthority.FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new AssetProblemResponse(
                "urn:renderweave:problem:" + rejection.code().name().toLowerCase(),
                "Asset content rejected",
                status.value(),
                "Asset content did not satisfy the admission kernel",
                null,
                rejection.code().name(),
                UUID.randomUUID().toString(),
                rejection.stage().name(),
                rejection.pointer(),
                rejection.limit().map(AssetAcceptanceAuthority.Limit::id).orElse(null),
                null
        ));
    }

    private ResponseEntity<AssetProblemResponse> conflictProblem(long currentAssetRevision) {
        var status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new AssetProblemResponse(
                "urn:renderweave:problem:asset-revision-conflict",
                "Asset revision conflict",
                status.value(),
                "expectedAssetRevision is no longer current",
                null,
                "ASSET_REVISION_CONFLICT",
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                currentAssetRevision
        ));
    }

    private ResponseEntity<AssetProblemResponse> problem(
            HttpStatus status,
            String code,
            String detail
    ) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new AssetProblemResponse(
                "urn:renderweave:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                code,
                status.value(),
                detail,
                null,
                code,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null
        ));
    }

    private ResponseEntity<?> downloaded(AssetApplication.DownloadedContent content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .header("Content-Disposition", contentDisposition(content.sourceFileName()))
                .body(content.bytes());
    }

    private static String contentDisposition(String sourceFileName) {
        if (sourceFileName == null) {
            return "attachment";
        }
        String encoded = URLEncoder.encode(sourceFileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }

    private static AssetCatalogEntryResponse catalogEntry(AssetApplication.CatalogEntry entry) {
        return new AssetCatalogEntryResponse(
                entry.assetId().value(),
                entry.kind().name(),
                entry.lifecycle().name(),
                entry.displayName(),
                entry.tags(),
                entry.sourceFileName(),
                entry.updatedAt().toString()
        );
    }

    private static AssetContentVersionResponse versionEntry(
            AssetApplication.ContentVersionEntry entry
    ) {
        return new AssetContentVersionResponse(
                entry.contentVersion(),
                entry.sha256(),
                entry.mediaType(),
                entry.byteLength(),
                entry.sourceFileName(),
                entry.createdAt().toString()
        );
    }

    private static AssetAcceptanceAuthority.AssetKind requireKind(String raw) {
        if (raw == null) {
            throw new InvalidAssetApiRequestException("kind is required");
        }
        try {
            return AssetAcceptanceAuthority.AssetKind.valueOf(raw);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidAssetApiRequestException(
                    "kind must be one of IMAGE, FONT"
            );
        }
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new InvalidAssetApiRequestException(
                    "Idempotency-Key must be non-blank and at most 128 characters"
            );
        }
    }

    private static void requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()
                || displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_SCALARS) {
            throw new InvalidAssetApiRequestException(
                    "displayName must be non-blank and at most "
                            + MAX_DISPLAY_NAME_SCALARS + " Unicode scalars"
            );
        }
    }

    private static List<String> requireTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        if (tags.size() > MAX_TAGS) {
            throw new InvalidAssetApiRequestException("at most " + MAX_TAGS + " tags");
        }
        var result = new ArrayList<String>(tags.size());
        for (String tag : tags) {
            if (tag == null || tag.isBlank()
                    || tag.codePointCount(0, tag.length()) > MAX_TAG_SCALARS) {
                throw new InvalidAssetApiRequestException(
                        "each tag must be non-blank and at most "
                                + MAX_TAG_SCALARS + " Unicode scalars"
                );
            }
            result.add(tag);
        }
        return List.copyOf(result);
    }

    private static void requireFilterTags(List<String> tags) {
        if (tags == null) {
            return;
        }
        if (tags.size() > MAX_TAGS) {
            throw new InvalidAssetApiRequestException(
                    "at most " + MAX_TAGS + " filter tags per selector"
            );
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()
                    || tag.codePointCount(0, tag.length()) > MAX_TAG_SCALARS) {
                throw new InvalidAssetApiRequestException(
                        "each filter tag must be non-blank and at most "
                                + MAX_TAG_SCALARS + " Unicode scalars"
                );
            }
        }
    }

    private static void requireSourceFileName(String sourceFileName) {
        if (sourceFileName != null && (sourceFileName.isBlank()
                || sourceFileName.codePointCount(0, sourceFileName.length())
                > MAX_SOURCE_FILE_NAME_SCALARS)) {
            throw new InvalidAssetApiRequestException(
                    "sourceFileName must be at most " + MAX_SOURCE_FILE_NAME_SCALARS
                            + " Unicode scalars"
            );
        }
    }

    private static AssetApplication.InvocationRef invocation() {
        return AssetApplication.InvocationRef.serverCreated(UUID.randomUUID().toString());
    }

    private static AssetApplication.AssetId assetId(String raw) {
        try {
            return AssetApplication.AssetId.of(raw);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidAssetApiRequestException(
                    "assetId must be a canonical lowercase UUID v4"
            );
        }
    }

    private static URI assetUri(AssetApplication.AssetId assetId) {
        return URI.create("/api/v1/assets/" + assetId.value());
    }

    record UpdateAssetMetadataRequest(String displayName, List<String> tags) {
    }

    record AssetResponse(
            String assetId,
            String disclosure,
            String kind,
            String lifecycle,
            long assetRevision,
            long currentContentVersion,
            String displayName,
            List<String> tags,
            String sourceFileName,
            String mediaType,
            long byteLength,
            String sha256,
            JsonNode descriptor,
            String createdAt,
            String updatedAt
    ) {
    }

    record OpaqueAssetResponse(String assetId, String disclosure) {
    }

    record AssetCatalogEntryResponse(
            String assetId,
            String kind,
            String lifecycle,
            String displayName,
            List<String> tags,
            String sourceFileName,
            String updatedAt
    ) {
    }

    record AssetCatalogResponse(List<AssetCatalogEntryResponse> items, String nextCursor) {
    }

    record AssetContentVersionResponse(
            long contentVersion,
            String sha256,
            String mediaType,
            long byteLength,
            String sourceFileName,
            String createdAt
    ) {
    }

    record AssetVersionsResponse(List<AssetContentVersionResponse> items) {
    }

    record AssetProblemResponse(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String code,
            String traceId,
            String stage,
            String pointer,
            String limit,
            Long currentAssetRevision
    ) {
    }
}
