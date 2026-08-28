package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.CapabilityContract;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact static capability catalog for a frozen closure. The module consumes Template-owned
 * semantic values, never serialized JSON text, and hides Expression input traversal from the
 * Evaluator and capability Adapter.
 */
final class CapabilityDeclarations {

    sealed interface Outcome permits Declared, DeclarationFault {
    }

    record Declared(Set<CapabilityContract> contracts, int sourceCount) implements Outcome {
        Declared {
            Objects.requireNonNull(contracts, "contracts");
            if (sourceCount < 0) {
                throw new IllegalArgumentException("sourceCount must be non-negative");
            }
            var copy = EnumSet.noneOf(CapabilityContract.class);
            copy.addAll(contracts);
            contracts = Set.copyOf(copy);
        }

        String canonicalContractIdentity() {
            return contracts.stream()
                    .map(CapabilityContract::contractId)
                    .sorted()
                    .collect(Collectors.joining(","));
        }
    }

    record DeclarationFault() implements Outcome {
    }

    private CapabilityDeclarations() {
    }

    static Outcome scan(ClosureSnapshot closure, DesignSemanticAuthority semantics) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(semantics, "semantics");
        var contracts = EnumSet.noneOf(CapabilityContract.class);
        int sourceCount = 0;
        for (var snapshot : closure.snapshots()) {
            var interpretation = semantics.interpret(snapshot.canonicalDesignDslUtf8());
            if (!(interpretation instanceof DesignSemanticAuthority.Interpreted interpreted)) {
                return new DeclarationFault();
            }
            var definitionsValue = interpreted.document().members().get("definitions");
            if (!(definitionsValue instanceof ArrayNode definitions)) {
                return new DeclarationFault();
            }
            for (var definitionValue : definitions.items()) {
                if (!(definitionValue instanceof ObjectNode definition)) {
                    return new DeclarationFault();
                }
                var definitionKind = text(definition, "kind");
                if (definitionKind == null) {
                    return new DeclarationFault();
                }
                if (!"expression".equals(definitionKind)) {
                    continue;
                }
                var inputsValue = definition.members().get("inputs");
                if (!(inputsValue instanceof ArrayNode inputs)) {
                    return new DeclarationFault();
                }
                for (var inputValue : inputs.items()) {
                    if (!(inputValue instanceof ObjectNode input)
                            || !(input.members().get("source") instanceof ObjectNode source)) {
                        return new DeclarationFault();
                    }
                    var sourceKind = text(source, "kind");
                    if (sourceKind == null) {
                        return new DeclarationFault();
                    }
                    if (!"capability".equals(sourceKind)) {
                        continue;
                    }
                    var contract = contract(text(source, "capability"), text(source, "operation"));
                    if (contract == null) {
                        return new DeclarationFault();
                    }
                    contracts.add(contract);
                    sourceCount++;
                }
            }
        }
        return new Declared(contracts, sourceCount);
    }

    private static CapabilityContract contract(String capability, String operation) {
        if ("CLOCK".equals(capability)
                && ("UTC_DATE".equals(operation) || "UTC_TIME".equals(operation))) {
            return CapabilityContract.CLOCK_1_0;
        }
        if ("RANDOM".equals(capability) && "UNIFORM_DECIMAL_0_1".equals(operation)) {
            return CapabilityContract.RANDOM_1_0;
        }
        return null;
    }

    private static String text(ObjectNode object, String member) {
        return object.members().get(member) instanceof Text text ? text.value() : null;
    }
}
