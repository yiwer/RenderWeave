package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonWriter;
import cn.hbads.renderweave.schema.draft.DraftSnapshot;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaPage;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaListSort;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaOriginFilter;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaService;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaSnapshot;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/static-schemas")
final class StaticSchemaController {

    private static final MediaType JSON_SCHEMA = MediaType.parseMediaType("application/schema+json");

    private final StaticSchemaService statics;
    private final ObjectMapper json;
    private final SchemaDefinitionJsonWriter definitionWriter = new SchemaDefinitionJsonWriter();

    StaticSchemaController(StaticSchemaService statics, ObjectMapper json) {
        this.statics = statics;
        this.json = json;
    }

    @PostMapping
    ResponseEntity<StaticSchemaResponse> publish(@RequestBody PublishStaticSchemaRequest request) {
        if (request.schemaKey() == null) {
            throw new InvalidApiRequestException("schemaKey is required");
        }
        if (request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new InvalidApiRequestException("expectedRevision must be a non-negative integer");
        }
        if (request.versionTag() == null) {
            throw new InvalidApiRequestException("versionTag is required");
        }
        var snapshot = statics.publish(
                request.schemaKey(),
                request.expectedRevision(),
                request.versionTag(),
                request.releaseNote()
        );
        return ResponseEntity
                .created(staticUri(snapshot))
                .body(toResponse(snapshot));
    }

    @GetMapping
    StaticSchemaListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "PUBLISHED_DESC") String sort,
            @RequestParam(defaultValue = "ALL") String origin
    ) {
        if (page < 1) {
            throw new InvalidApiRequestException("page must be at least 1");
        }
        if (size < 1 || size > 100) {
            throw new InvalidApiRequestException("size must be between 1 and 100");
        }
        return toListResponse(statics.list(
                page,
                size,
                search,
                parseStaticSort(sort),
                parseOriginFilter(origin)
        ));
    }

    @GetMapping("/{schemaKey}/{versionTag}")
    StaticSchemaResponse get(
            @PathVariable String schemaKey,
            @PathVariable String versionTag
    ) {
        return toResponse(statics.get(schemaKey, versionTag));
    }

    @GetMapping("/{schemaKey}/{versionTag}/definition")
    ResponseEntity<String> downloadDefinition(
            @PathVariable String schemaKey,
            @PathVariable String versionTag
    ) {
        var snapshot = statics.get(schemaKey, versionTag);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(definitionWriter.write(snapshot.definition()));
    }

    @GetMapping("/{schemaKey}/{versionTag}/compiled-json-schema")
    ResponseEntity<String> downloadCompiledJsonSchema(
            @PathVariable String schemaKey,
            @PathVariable String versionTag
    ) {
        var snapshot = statics.get(schemaKey, versionTag);
        return ResponseEntity.ok()
                .contentType(JSON_SCHEMA)
                .body(snapshot.compiledJsonSchema());
    }

    @PostMapping("/{schemaKey}/{versionTag}/copies")
    ResponseEntity<DraftController.DraftResponse> copy(
            @PathVariable String schemaKey,
            @PathVariable String versionTag,
            @RequestBody CopyStaticSchemaRequest request
    ) {
        if (request.schemaKey() == null) {
            throw new InvalidApiRequestException("schemaKey is required");
        }
        if (request.displayName() == null) {
            throw new InvalidApiRequestException("displayName is required");
        }
        var draft = statics.copyToDraft(
                schemaKey,
                versionTag,
                request.schemaKey(),
                request.displayName()
        );
        return ResponseEntity
                .created(URI.create("/api/v1/schema-drafts/" + draft.schemaKey().value()))
                .body(toDraftResponse(draft));
    }

    private StaticSchemaResponse toResponse(StaticSchemaSnapshot snapshot) {
        return new StaticSchemaResponse(
                snapshot.reference().schemaKey().value(),
                snapshot.reference().versionTag().value(),
                snapshot.origin().name(),
                snapshot.sourceDraftRevision().orElse(null),
                readDefinition(definitionWriter.write(snapshot.definition())),
                snapshot.compilerVersion(),
                snapshot.releaseNote().orElse(null),
                snapshot.referenceDepth(),
                snapshot.publishedAt()
        );
    }

    private static StaticSchemaListSort parseStaticSort(String value) {
        try {
            return StaticSchemaListSort.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidApiRequestException("unsupported StaticSchema list sort: " + value, exception);
        }
    }

    private static StaticSchemaOriginFilter parseOriginFilter(String value) {
        try {
            return StaticSchemaOriginFilter.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidApiRequestException("unsupported StaticSchema origin filter: " + value, exception);
        }
    }

    private StaticSchemaListResponse toListResponse(StaticSchemaPage page) {
        var items = page.items().stream()
                .map(item -> new StaticSchemaSummaryResponse(
                        item.reference().schemaKey().value(),
                        item.reference().versionTag().value(),
                        item.origin().name(),
                        item.displayName(),
                        item.fieldCount(),
                        item.referenceDepth(),
                        item.publishedAt()
                ))
                .toList();
        return new StaticSchemaListResponse(items, page.page(), page.size(), page.total());
    }

    private DraftController.DraftResponse toDraftResponse(DraftSnapshot snapshot) {
        var resolved = new LinkedHashMap<String, Long>();
        snapshot.resolvedRevisions().forEach((key, revision) -> resolved.put(key.value(), revision));
        return new DraftController.DraftResponse(
                snapshot.schemaKey().value(),
                snapshot.revision(),
                readDefinition(definitionWriter.write(snapshot.definition())),
                snapshot.creationSource().name(),
                snapshot.createdAt(),
                snapshot.updatedAt(),
                snapshot.savedAt(),
                resolved
        );
    }

    private JsonNode readDefinition(String definitionJson) {
        try {
            return json.readTree(definitionJson);
        } catch (JacksonException exception) {
            throw new IllegalStateException("A valid stored definition could not be rendered", exception);
        }
    }

    private static URI staticUri(StaticSchemaSnapshot snapshot) {
        return URI.create("/api/v1/static-schemas/"
                + snapshot.reference().schemaKey().value()
                + "/"
                + snapshot.reference().versionTag().value());
    }

    record PublishStaticSchemaRequest(
            String schemaKey,
            Long expectedRevision,
            String versionTag,
            String releaseNote
    ) {
    }

    record CopyStaticSchemaRequest(String schemaKey, String displayName) {
    }

    record StaticSchemaResponse(
            String schemaKey,
            String versionTag,
            String origin,
            Long sourceDraftRevision,
            JsonNode definition,
            String compilerVersion,
            String releaseNote,
            int referenceDepth,
            Instant publishedAt
    ) {
    }

    record StaticSchemaSummaryResponse(
            String schemaKey,
            String versionTag,
            String origin,
            String displayName,
            int fieldCount,
            int referenceDepth,
            Instant publishedAt
    ) {
    }

    record StaticSchemaListResponse(
            List<StaticSchemaSummaryResponse> items,
            int page,
            int size,
            long total
    ) {
    }
}
