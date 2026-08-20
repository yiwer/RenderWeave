package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * canonical closure manifest 与 closureDigest（冻结票据 15 §150）：顶层绑定 ownerScope 与
 * root {templateId,revision}；snapshots[] 按 templateId UTF-8 排序，每条携带
 * templateId/revision/staticSchemaRef/contentHash/dslVersion/expressionProfile；edges[]
 * 按 parentTemplateId、parentRevision、useId 排序。readiness、display name 与数据库 row
 * version 排除。
 */
final class ClosureManifests {

    private ClosureManifests() {
    }

    static String digest(ClosureSnapshot closure) {
        return RenderingDigests.closureDigest(canonicalManifest(closure));
    }

    static byte[] canonicalManifest(ClosureSnapshot closure) {
        var members = new TreeMap<String, String>();
        members.put("ownerScope", CanonicalJson.string(closure.ownerScope().value()));

        var rootMembers = new TreeMap<String, String>();
        rootMembers.put("revision", CanonicalJson.decimal(
                java.math.BigDecimal.valueOf(closure.rootRevision())));
        rootMembers.put("templateId", CanonicalJson.string(closure.rootTemplateId().value()));
        members.put("root", CanonicalJson.object(rootMembers));

        var snapshots = new ArrayList<String>();
        for (var snapshot : closure.snapshots()) {
            var entry = new TreeMap<String, String>();
            entry.put("contentHash", CanonicalJson.string(snapshot.contentHash()));
            entry.put("dslVersion", CanonicalJson.string(snapshot.dslVersion()));
            entry.put("expressionProfile", CanonicalJson.string(snapshot.expressionProfile()));
            entry.put("revision", CanonicalJson.decimal(
                    java.math.BigDecimal.valueOf(snapshot.revision())));
            var schemaMembers = new TreeMap<String, String>();
            schemaMembers.put("schemaKey",
                    CanonicalJson.string(snapshot.staticSchema().schemaKey().value()));
            schemaMembers.put("versionTag",
                    CanonicalJson.string(snapshot.staticSchema().versionTag().value()));
            entry.put("staticSchemaRef", CanonicalJson.object(schemaMembers));
            entry.put("templateId", CanonicalJson.string(snapshot.templateId().value()));
            snapshots.add(CanonicalJson.object(entry));
        }
        members.put("snapshots", CanonicalJson.array(snapshots));

        var edges = new ArrayList<String>();
        for (var edge : closure.edges()) {
            var entry = new TreeMap<String, String>();
            entry.put("childRevision", CanonicalJson.decimal(
                    java.math.BigDecimal.valueOf(edge.childRevision())));
            entry.put("childTemplateId", CanonicalJson.string(edge.childTemplateId().value()));
            entry.put("parentRevision", CanonicalJson.decimal(
                    java.math.BigDecimal.valueOf(edge.parentRevision())));
            entry.put("parentTemplateId", CanonicalJson.string(edge.parentTemplateId().value()));
            entry.put("useId", CanonicalJson.string(edge.useId()));
            edges.add(CanonicalJson.object(entry));
        }
        members.put("edges", CanonicalJson.array(edges));

        return CanonicalJson.object(members).getBytes(StandardCharsets.UTF_8);
    }
}
