package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetReferencePort;
import cn.hbads.renderweave.template.api.AssetReferenceAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * App bridge for the Asset-owned {@link AssetReferencePort}: reads the Template-owned
 * {@link AssetReferenceAuthority} current-only proof, redacts it to the caller's Template
 * read permission (the caller sees full counts, its readable Template ids and the
 * {@code redactedCount}), and fingerprints the complete sorted reference set so the delete
 * confirmation token binds every reference, readable or not. This adapter is the only
 * Asset-side view of Template reference facts; no aggregates, tables or transactions are
 * shared between the contexts.
 */
class TemplateAssetReferencePortAdapter implements AssetReferencePort {

    private final AssetReferenceAuthority authority;
    private final TemplatePersistence persistence;
    private final OwnerScopeAuthority ownerScopes;

    TemplateAssetReferencePortAdapter(
            AssetReferenceAuthority authority,
            TemplatePersistence persistence,
            OwnerScopeAuthority ownerScopes
    ) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.ownerScopes = Objects.requireNonNull(ownerScopes, "ownerScopes");
    }

    @Override
    public ReferenceOutcome references(
            AssetApplication.InvocationRef invocation,
            AssetApplication.AssetId assetId
    ) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(assetId, "assetId");
        var outcome = authority.references(assetId.value());
        if (outcome instanceof AssetReferenceAuthority.ReferencesUnavailable) {
            return new ReferencesUnavailable();
        }
        var templateIds = ((AssetReferenceAuthority.ReferencesReadable) outcome)
                .references()
                .templateIds();
        var allIds = templateIds.stream()
                .map(TemplateApplication.TemplateId::value)
                .sorted()
                .toList();
        var fingerprint = fingerprint(allIds);

        var readable = new ArrayList<String>();
        for (var templateId : templateIds) {
            var located = persistence.locate(templateId);
            if (located instanceof TemplatePersistence.LocateUnavailable) {
                return new ReferencesUnavailable();
            }
            if (located instanceof TemplatePersistence.LocateNotFound) {
                // Reference table and aggregate must agree; treat as unreadable (redacted).
                continue;
            }
            var metadata = ((TemplatePersistence.Located) located).metadata();
            var access = ownerScopes.authorizeExisting(
                    TemplateApplication.TemplateInvocationRef.serverCreated(invocation.value()),
                    metadata.ownerScope(),
                    OwnerScopeAuthority.ExistingOperation.READ
            );
            if (access instanceof OwnerScopeAuthority.ExistingGranted granted
                    && granted.disclosure() == OwnerScopeAuthority.Disclosure.READABLE) {
                readable.add(templateId.value());
            } else if (access instanceof OwnerScopeAuthority.ExistingUnavailable) {
                return new ReferencesUnavailable();
            }
        }
        readable.sort(String::compareTo);
        return new ReferencesReadable(new ReferenceProof(
                allIds.size(),
                readable,
                allIds.size() - readable.size(),
                fingerprint
        ));
    }

    /** SHA-256 over the domain-separated sorted id list; the delete token binds this. */
    static String fingerprint(List<String> sortedTemplateIds) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("renderweave-template-asset-ref-v1".getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            for (String id : sortedTemplateIds) {
                digest.update(id.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
