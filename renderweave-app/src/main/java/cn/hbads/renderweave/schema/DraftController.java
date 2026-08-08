package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonWriter;
import cn.hbads.renderweave.schema.draft.DraftService;
import cn.hbads.renderweave.schema.draft.DraftPage;
import cn.hbads.renderweave.schema.draft.DraftHistoryPage;
import cn.hbads.renderweave.schema.draft.DraftListSort;
import cn.hbads.renderweave.schema.draft.DraftRevisionSnapshot;
import cn.hbads.renderweave.schema.draft.DraftSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@RequestMapping("/api/v1/schema-drafts")
final class DraftController {

    private final DraftService drafts;
    private final ObjectMapper json;
    private final SchemaDefinitionJsonWriter definitionWriter = new SchemaDefinitionJsonWriter();

    DraftController(DraftService drafts, ObjectMapper json) {
        this.drafts = drafts;
        this.json = json;
    }

    @PostMapping
    ResponseEntity<DraftResponse> create(@RequestBody CreateDraftRequest request) {
        if (request.schemaKey() == null) {
            throw new InvalidApiRequestException("schemaKey is required");
        }
        if (request.definition() == null) {
            throw new InvalidApiRequestException("definition is required");
        }

        var snapshot = drafts.create(request.schemaKey(), writeTree(request.definition()));
        return ResponseEntity
                .created(URI.create("/api/v1/schema-drafts/" + snapshot.schemaKey().value()))
                .body(toResponse(snapshot));
    }

    @GetMapping
    DraftListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "UPDATED_DESC") String sort
    ) {
        if (page < 1) {
            throw new InvalidApiRequestException("page must be at least 1");
        }
        if (size < 1 || size > 100) {
            throw new InvalidApiRequestException("size must be between 1 and 100");
        }
        return toListResponse(drafts.list(page, size, search, parseDraftSort(sort)));
    }

    @GetMapping("/{schemaKey}")
    DraftResponse get(@PathVariable String schemaKey) {
        return toResponse(drafts.get(schemaKey));
    }

    @PutMapping("/{schemaKey}")
    DraftResponse save(
            @PathVariable String schemaKey,
            @RequestBody SaveDraftRequest request
    ) {
        if (request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new InvalidApiRequestException("expectedRevision must be a non-negative integer");
        }
        if (request.definition() == null) {
            throw new InvalidApiRequestException("definition is required");
        }
        return toResponse(drafts.save(
                schemaKey,
                request.expectedRevision(),
                writeTree(request.definition())
        ));
    }

    @GetMapping("/{schemaKey}/revisions")
    DraftHistoryResponse history(
            @PathVariable String schemaKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 1) {
            throw new InvalidApiRequestException("page must be at least 1");
        }
        if (size < 1 || size > 100) {
            throw new InvalidApiRequestException("size must be between 1 and 100");
        }
        return toHistoryResponse(drafts.history(schemaKey, page, size));
    }

    @GetMapping("/{schemaKey}/revisions/{revision}")
    DraftRevisionResponse revision(
            @PathVariable String schemaKey,
            @PathVariable long revision
    ) {
        if (revision < 0) {
            throw new InvalidApiRequestException("revision must not be negative");
        }
        return toRevisionResponse(drafts.getRevision(schemaKey, revision));
    }

    @DeleteMapping("/{schemaKey}")
    ResponseEntity<Void> delete(
            @PathVariable String schemaKey,
            @RequestParam Long expectedRevision
    ) {
        if (expectedRevision == null || expectedRevision < 0) {
            throw new InvalidApiRequestException("expectedRevision must be a non-negative integer");
        }
        drafts.delete(schemaKey, expectedRevision);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{schemaKey}/restore")
    DraftResponse restore(
            @PathVariable String schemaKey,
            @RequestBody RestoreDraftRequest request
    ) {
        if (request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new InvalidApiRequestException("expectedRevision must be a non-negative integer");
        }
        if (request.sourceRevision() == null || request.sourceRevision() < 0) {
            throw new InvalidApiRequestException("sourceRevision must be a non-negative integer");
        }
        return toResponse(drafts.restore(
                schemaKey,
                request.expectedRevision(),
                request.sourceRevision()
        ));
    }

    @PostMapping("/{schemaKey}/copies")
    ResponseEntity<DraftResponse> copy(
            @PathVariable String schemaKey,
            @RequestBody CopyDraftRequest request
    ) {
        if (request.schemaKey() == null) {
            throw new InvalidApiRequestException("schemaKey is required");
        }
        if (request.displayName() == null) {
            throw new InvalidApiRequestException("displayName is required");
        }
        var snapshot = drafts.copyCurrent(schemaKey, request.schemaKey(), request.displayName());
        return ResponseEntity
                .created(URI.create("/api/v1/schema-drafts/" + snapshot.schemaKey().value()))
                .body(toResponse(snapshot));
    }

    private String writeTree(JsonNode definition) {
        try {
            return json.writeValueAsString(definition);
        } catch (JacksonException exception) {
            throw new InvalidApiRequestException("definition cannot be encoded", exception);
        }
    }

    private static DraftListSort parseDraftSort(String value) {
        try {
            return DraftListSort.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidApiRequestException("unsupported Draft list sort: " + value, exception);
        }
    }

    private DraftResponse toResponse(DraftSnapshot snapshot) {
        try {
            return new DraftResponse(
                    snapshot.schemaKey().value(),
                    snapshot.revision(),
                    json.readTree(definitionWriter.write(snapshot.definition())),
                    snapshot.creationSource().name(),
                    snapshot.createdAt(),
                    snapshot.updatedAt(),
                    snapshot.savedAt(),
                    stringKeyed(snapshot.resolvedRevisions())
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("A valid stored definition could not be rendered", exception);
        }
    }

    private DraftHistoryResponse toHistoryResponse(DraftHistoryPage history) {
        var items = history.items().stream()
                .map(item -> new DraftRevisionSummary(
                        item.revision(),
                        item.definition().displayName(),
                        item.definition().fields().size(),
                        item.savedAt()
                ))
                .toList();
        return new DraftHistoryResponse(items, history.page(), history.size(), history.total());
    }

    private DraftListResponse toListResponse(DraftPage page) {
        var items = page.items().stream()
                .map(item -> new DraftSummaryResponse(
                        item.schemaKey().value(),
                        item.revision(),
                        item.creationSource().name(),
                        item.displayName(),
                        item.fieldCount(),
                        item.createdAt(),
                        item.updatedAt(),
                        item.savedAt()
                ))
                .toList();
        return new DraftListResponse(items, page.page(), page.size(), page.total());
    }

    private DraftRevisionResponse toRevisionResponse(DraftRevisionSnapshot snapshot) {
        return new DraftRevisionResponse(
                snapshot.schemaKey().value(),
                snapshot.revision(),
                readDefinition(definitionWriter.write(snapshot.definition())),
                snapshot.savedAt()
        );
    }

    private JsonNode readDefinition(String definitionJson) {
        try {
            return json.readTree(definitionJson);
        } catch (JacksonException exception) {
            throw new IllegalStateException("A valid stored definition could not be rendered", exception);
        }
    }

    private static Map<String, Long> stringKeyed(
            Map<cn.hbads.renderweave.schema.identity.SchemaKey, Long> revisions
    ) {
        var result = new LinkedHashMap<String, Long>();
        revisions.forEach((key, revision) -> result.put(key.value(), revision));
        return result;
    }

    record CreateDraftRequest(String schemaKey, JsonNode definition) {
    }

    record SaveDraftRequest(Long expectedRevision, JsonNode definition) {
    }

    record RestoreDraftRequest(Long expectedRevision, Long sourceRevision) {
    }

    record CopyDraftRequest(String schemaKey, String displayName) {
    }

    record DraftResponse(
            String schemaKey,
            long revision,
            JsonNode definition,
            String creationSource,
            Instant createdAt,
            Instant updatedAt,
            Instant savedAt,
            Map<String, Long> resolvedRevisions
    ) {
    }

    record DraftSummaryResponse(
            String schemaKey,
            long revision,
            String creationSource,
            String displayName,
            int fieldCount,
            Instant createdAt,
            Instant updatedAt,
            Instant savedAt
    ) {
    }

    record DraftListResponse(
            List<DraftSummaryResponse> items,
            int page,
            int size,
            long total
    ) {
    }

    record DraftHistoryResponse(
            List<DraftRevisionSummary> items,
            int page,
            int size,
            long total
    ) {
    }

    record DraftRevisionSummary(
            long revision,
            String displayName,
            int fieldCount,
            Instant savedAt
    ) {
    }

    record DraftRevisionResponse(
            String schemaKey,
            long revision,
            JsonNode definition,
            Instant savedAt
    ) {
    }
}
