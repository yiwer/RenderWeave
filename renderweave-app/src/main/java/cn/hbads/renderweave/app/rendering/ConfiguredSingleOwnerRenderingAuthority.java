package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.OwnerScope;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Dev/test single-owner Host adapter for the closed Template capability catalog. */
final class ConfiguredSingleOwnerRenderingAuthority implements RenderingAuthority {

    private static final String READ = "template.read";
    private static final String RENDER = "template.render";
    private static final int MAX_OUTSTANDING_RECHECKS = 4096;
    private static final Set<String> KNOWN = Set.of(
            READ,
            "template.create",
            "template.update",
            "template.delete",
            RENDER);

    private final OwnerScope ownerScope;
    private final TemplatePersistence templates;
    private final Set<String> capabilities;
    private final Map<String, IssuedGrant> issuedRechecks = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, IssuedGrant> eldest) {
                    return size() > MAX_OUTSTANDING_RECHECKS;
                }
            });

    ConfiguredSingleOwnerRenderingAuthority(
            String ownerScope,
            Set<String> capabilities,
            TemplatePersistence templates
    ) {
        this.ownerScope = new OwnerScope(ownerScope);
        this.templates = Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(capabilities, "capabilities");
        if (!KNOWN.containsAll(capabilities)) {
            throw new IllegalArgumentException("unknown Template single-owner capability");
        }
        this.capabilities = Set.copyOf(capabilities);
    }

    @Override
    public AuthorizationDecision authorize(
            RenderInvocationRef invocation,
            TemplateId rootTemplateId,
            RenderPurpose purpose
    ) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(rootTemplateId, "rootTemplateId");
        Objects.requireNonNull(purpose, "purpose");
        var target = locate(rootTemplateId);
        if (target == TargetState.HIDDEN) {
            return new Hidden();
        }
        if (target == TargetState.UNAVAILABLE) {
            return new Unavailable();
        }
        if (!permitted(purpose)) {
            return capabilities.contains(READ) ? new Forbidden() : new Hidden();
        }
        var identity = new RecheckIdentity(UUID.randomUUID().toString());
        issuedRechecks.put(identity.value(), new IssuedGrant(rootTemplateId, purpose));
        return new Authorized(
                ownerScope,
                authorizationContextDigest(invocation, purpose),
                identity,
                capabilities.contains(READ) ? Disclosure.READABLE : Disclosure.OPAQUE);
    }

    private String authorizationContextDigest(RenderInvocationRef invocation, RenderPurpose purpose) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("renderweave-authorization-context/1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(invocation.value().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ownerScope.value().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(purpose.name().getBytes(StandardCharsets.UTF_8));
            for (var capability : capabilities.stream().sorted().toList()) {
                digest.update((byte) 0);
                digest.update(capability.getBytes(StandardCharsets.UTF_8));
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public RecheckDecision recheck(RecheckIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        var grant = issuedRechecks.remove(identity.value());
        if (grant == null) {
            return new RecheckUnavailable();
        }
        var target = locate(grant.rootTemplateId());
        if (target == TargetState.HIDDEN) {
            return new RecheckHidden();
        }
        if (target == TargetState.UNAVAILABLE) {
            return new RecheckUnavailable();
        }
        if (permitted(grant.purpose())) {
            return new RecheckGranted(
                    capabilities.contains(READ) ? Disclosure.READABLE : Disclosure.OPAQUE);
        }
        return capabilities.contains(READ)
                ? new RecheckForbidden()
                : new RecheckHidden();
    }

    private boolean permitted(RenderPurpose purpose) {
        return capabilities.contains(RENDER)
                && (purpose == RenderPurpose.FORMAL_OUTPUT || capabilities.contains(READ));
    }

    private TargetState locate(TemplateId templateId) {
        var located = templates.locate(templateId);
        if (located instanceof TemplatePersistence.LocateUnavailable || located == null) {
            return TargetState.UNAVAILABLE;
        }
        if (!(located instanceof TemplatePersistence.Located existing)
                || existing.metadata().lifecycle() != TemplatePersistence.Lifecycle.ACTIVE
                || !ownerScope.value().equals(existing.metadata().ownerScope().value())) {
            return TargetState.HIDDEN;
        }
        return TargetState.VISIBLE;
    }

    private record IssuedGrant(TemplateId rootTemplateId, RenderPurpose purpose) {
    }

    private enum TargetState {
        VISIBLE,
        HIDDEN,
        UNAVAILABLE
    }
}
