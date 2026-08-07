package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.draft.StaticReferenceTarget;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PublishStaticSchema(
        SchemaKey schemaKey,
        long expectedRevision,
        VersionTag versionTag,
        String definitionJson,
        String compiledJsonSchema,
        String compilerVersion,
        Optional<String> releaseNote,
        List<StaticReferenceTarget> references,
        int referenceDepth
) {

    public PublishStaticSchema {
        Objects.requireNonNull(schemaKey, "schemaKey");
        Objects.requireNonNull(versionTag, "versionTag");
        Objects.requireNonNull(definitionJson, "definitionJson");
        Objects.requireNonNull(compiledJsonSchema, "compiledJsonSchema");
        Objects.requireNonNull(compilerVersion, "compilerVersion");
        releaseNote = releaseNote == null ? Optional.empty() : releaseNote;
        references = List.copyOf(references);
    }
}
