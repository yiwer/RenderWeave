package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualGroundingContractTest {
    private static final String IMAGE_ID = "a".repeat(64);
    private final VisualGroundingJsonCodec codec = new VisualGroundingJsonCodec();

    @Test
    void mapsViewEvidenceToOriginalAndAcceptsAThreeLevelSpatialPlan() throws Exception {
        var views = views();
        var observed = codec.parseElements(elementsJson(), views, List.of(IMAGE_ID));
        var hierarchy = codec.parseHierarchy(hierarchyJson(), observed.inventory(), observed.grounding());
        var bindings = codec.parseBindings(
                bindingsJson(), observed.inventory(), hierarchy.hierarchy(), observed.grounding(),
                hierarchy.entityRegions()
        );

        assertEquals(5, observed.grounding().regions().size());
        assertEquals(new CandidateBoundingBox(0, 0, 10_000, 10_000),
                observed.grounding().requireRegion("root").evidence().getFirst().boundingBox());
        assertEquals(List.of("item-a", "item-b"),
                observed.grounding().regionIdsForElement("item-label"));
        assertEquals(VisualHierarchyPlan.VERSION_V2, hierarchy.hierarchy().contractVersion());
        assertEquals(List.of("item-a", "item-b"),
                hierarchy.entityRegions().requireEntity("item").regionIds());
        assertEquals(2, bindings.bindings().size());

        var checkpoint = LiveWorkflowCheckpoint.started()
                .elementsGrounded(observed.inventory(), observed.grounding(), 1)
                .hierarchyGrounded(hierarchy.hierarchy(), hierarchy.entityRegions(), 2)
                .elementsBound(bindings, 3);
        var restored = new LiveWorkflowJsonCodec().parse(new LiveWorkflowJsonCodec().write(checkpoint));
        assertEquals(LiveWorkflowCheckpoint.VERSION, restored.checkpointVersion());
        assertEquals(observed.grounding(), restored.groundingPlan());
        assertEquals(hierarchy.entityRegions(), restored.entityRegionPlan());
    }

    @Test
    void rejectsUnknownViewsSpatialEscapesOverlapCyclesAndRepeatDrift() throws Exception {
        var views = views();
        assertDiagnostic(elementsJson().replace("view-00-overview-00", "unknown-view"), views,
                "VISUAL_GROUNDING_REGION_INVALID");
        assertDiagnostic(elementsJson().replaceFirst("\"left\":0", "\"left\":-1"), views,
                "VISUAL_GROUNDING_JSON_CONSTRUCTOR_INVALID");
        assertDiagnostic(elementsJson().replace(
                "\"left\":100,\"top\":100,\"right\":3000,\"bottom\":700",
                "\"left\":100,\"top\":2500,\"right\":3000,\"bottom\":2700"
        ), views, "VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION");
        assertDiagnostic(elementsJson().replace(
                "\"left\":0,\"top\":6000,\"right\":10000,\"bottom\":10000",
                "\"left\":0,\"top\":5000,\"right\":10000,\"bottom\":10000"
        ), views, "VISUAL_GROUNDING_SIBLING_OVERLAP");
        assertDiagnostic(elementsJson().replace(
                "\"regionId\":\"repeat\",\"parentRegionId\":\"root\"",
                "\"regionId\":\"repeat\",\"parentRegionId\":\"item-a\""
        ), views, "VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID");
        assertDiagnostic(elementsJson().replace(
                "\"repeatGroupId\":\"rows\",\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":6000",
                "\"repeatGroupId\":\"other\",\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":6000"
        ), views, "VISUAL_GROUNDING_PARENT_KIND_INVALID");
        assertDiagnostic(elementsJson().replace(
                "\"regionId\":\"repeat\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":1",
                "\"regionId\":\"repeat\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":2"
        ), views, "VISUAL_GROUNDING_READING_ORDER_GAP");
        assertDiagnostic(elementsJson().replace(
                "\"regionId\":\"header\",\"parentRegionId\":\"root\",\"kind\":\"SECTION\",\"multiplicity\":\"ONE\",\"readingOrder\":0",
                "\"regionId\":\"header\",\"parentRegionId\":\"root\",\"kind\":\"SECTION\",\"multiplicity\":\"ONE\",\"readingOrder\":1"
        ).replace(
                "\"regionId\":\"repeat\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":1",
                "\"regionId\":\"repeat\",\"parentRegionId\":\"root\",\"kind\":\"REPEATED_GROUP\",\"multiplicity\":\"MANY\",\"readingOrder\":0"
        ), views, "VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID");
    }

    @Test
    void rejectsStrictJsonAndSpatiallyInvalidHierarchyOrBinding() throws Exception {
        var views = views();
        var observed = codec.parseElements(elementsJson(), views, List.of(IMAGE_ID));

        assertEquals("VISUAL_GROUNDING_JSON_UNKNOWN_MEMBER", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replaceFirst("\\{", "{\"unexpected\":true,"), views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_DUPLICATE_MEMBER", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replaceFirst("\"contractVersion\":", "\"contractVersion\":\"bad\",\"contractVersion\":"),
                views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_TRAILING_CONTENT", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson() + "{}", views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_SHAPE_INVALID_REGION_READING_ORDER", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replace("\"readingOrder\":0", "\"readingOrder\":\"0\""),
                views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_SHAPE_INVALID_ROOT_REGIONS", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replaceFirst(
                        "\"regions\":\\[", "\"regions\":\"not-an-array\",\"ignored\":["
                ),
                views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replaceFirst("\"kind\":\"ROOT\"", "\"kind\":\"DOCUMENT\""),
                views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_ENUM_INVALID_ELEMENT_VALUE_HINT", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().replaceFirst("\"valueHint\":\"TEXT\"", "\"valueHint\":\"STRING\""),
                views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertEquals("VISUAL_GROUNDING_JSON_SYNTAX_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                elementsJson().substring(0, elementsJson().length() - 8), views, List.of(IMAGE_ID)
        )).diagnosticCode());
        assertDiagnostic(elementsJson().replace(
                "renderweave-visual-grounding/2.0", "renderweave-visual-grounding/9.0"
        ), views, "VISUAL_GROUNDING_VERSION_INVALID");

        assertEquals("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseHierarchy(
                hierarchyJson().replace(
                        "\"regionIds\":[\"item-a\",\"item-b\"]",
                        "\"regionIds\":[\"header\"]"
                ), observed.inventory(), observed.grounding()
        )).diagnosticCode());
        var hierarchy = codec.parseHierarchy(hierarchyJson(), observed.inventory(), observed.grounding());
        assertEquals("VISUAL_BINDINGS_V2_REGION_OWNERSHIP_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseBindings(
                bindingsJson().replace(
                        "{\"elementId\":\"title\",\"entityId\":\"document\"}",
                        "{\"elementId\":\"title\",\"entityId\":\"item\"}"
                ), observed.inventory(), hierarchy.hierarchy(), observed.grounding(), hierarchy.entityRegions()
        )).diagnosticCode());
    }

    @Test
    void boundedObservationNormalizationRepairsOnlyDocumentedKindsAndOneExactItemParent()
            throws Exception {
        var malformed = elementsJson()
                .replaceFirst("\"kind\":\"ROOT\"", "\"kind\":\"DOCUMENT\"")
                .replaceFirst("\"kind\":\"SECTION\"", "\"kind\":\"container\"")
                .replace(
                        "\"regionId\":\"item-a\",\"parentRegionId\":\"repeat\",\"kind\":\"ITEM\",\"multiplicity\":\"ONE\",\"readingOrder\":0,\"repeatGroupId\":\"rows\"",
                        "\"regionId\":\"item-a\",\"parentRegionId\":\"root\",\"kind\":\"item\",\"multiplicity\":\"ONE\",\"readingOrder\":3,\"repeatGroupId\":\"rows\""
                );

        assertEquals("VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                        malformed, views(), List.of(IMAGE_ID)
                )
        ).diagnosticCode());

        var normalized = codec.parseElements(
                malformed, views(), List.of(IMAGE_ID),
                VisualObservationNormalizationPolicy.BOUNDED_ENUM_AND_UNIQUE_ITEM_PARENT
        );
        assertEquals(VisualRegionKind.ROOT,
                normalized.grounding().requireRegion("root").kind());
        assertEquals(VisualRegionKind.GROUP,
                normalized.grounding().requireRegion("header").kind());
        assertEquals(VisualRegionKind.ITEM,
                normalized.grounding().requireRegion("item-a").kind());
        assertEquals("repeat",
                normalized.grounding().requireRegion("item-a").parentRegionId());
        assertEquals(0, normalized.grounding().requireRegion("item-a").readingOrder());
        assertEquals(3, normalized.normalizedRegionKinds());
        assertEquals(1, normalized.normalizedItemParents());
        assertEquals(1, normalized.normalizedReadingOrders());

        var unknownAlias = elementsJson().replaceFirst(
                "\"kind\":\"ROOT\"", "\"kind\":\"FIELD\""
        );
        assertEquals("VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                        unknownAlias, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy.BOUNDED_ENUM_AND_UNIQUE_ITEM_PARENT
                )
        ).diagnosticCode());

        var noExactParent = elementsJson().replace(
                "\"regionId\":\"item-a\",\"parentRegionId\":\"repeat\",\"kind\":\"ITEM\",\"multiplicity\":\"ONE\",\"readingOrder\":0,\"repeatGroupId\":\"rows\"",
                "\"regionId\":\"item-a\",\"parentRegionId\":\"header\",\"kind\":\"ITEM\",\"multiplicity\":\"ONE\",\"readingOrder\":0,\"repeatGroupId\":\"other\""
        );
        assertEquals("VISUAL_GROUNDING_PARENT_KIND_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                        noExactParent, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy.BOUNDED_ENUM_AND_UNIQUE_ITEM_PARENT
                )
        ).diagnosticCode());
    }

    @Test
    void boundedEvidenceOwnerNormalizationUsesOnlyUniqueMinimalCompatibleRegions()
            throws Exception {
        var malformed = elementsJson()
                .replace("\"regionIds\":[\"header\"]", "\"regionIds\":[\"item-a\"]")
                .replace("\"regionIds\":[\"repeat\"]", "\"regionIds\":[\"header\"]")
                .replace(
                        "\"regionIds\":[\"item-a\",\"item-b\"]",
                        "\"regionIds\":[\"item-a\"]"
                );

        assertEquals("VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                        malformed, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy.BOUNDED_ENUM_AND_UNIQUE_ITEM_PARENT
                )
        ).diagnosticCode());

        var normalized = codec.parseElements(
                malformed, views(), List.of(IMAGE_ID),
                VisualObservationNormalizationPolicy
                        .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_AND_EVIDENCE_OWNER
        );
        assertEquals(List.of("header"),
                normalized.grounding().regionIdsForElement("title"));
        assertEquals(List.of("repeat"),
                normalized.grounding().regionIdsForElement("row-group"));
        assertEquals(List.of("item-a", "item-b"),
                normalized.grounding().regionIdsForElement("item-label"));
        assertEquals(3, normalized.normalizedElementRegionOwners());

        var rootOnly = elementsJson().replace(
                "\"left\":100,\"top\":100,\"right\":3000,\"bottom\":700",
                "\"left\":100,\"top\":1900,\"right\":3000,\"bottom\":2100"
        );
        assertEquals("VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                        rootOnly, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy
                                .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_AND_EVIDENCE_OWNER
                )
        ).diagnosticCode());

        var unknownOwner = elementsJson().replace(
                "\"regionIds\":[\"header\"]", "\"regionIds\":[\"missing\"]"
        );
        assertEquals("VISUAL_GROUNDING_ELEMENT_REGION_UNKNOWN", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseElements(
                        unknownOwner, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy
                                .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_AND_EVIDENCE_OWNER
                )
        ).diagnosticCode());
    }

    @Test
    void boundedRepeatedItemSlotOwnerNormalizationUsesOnlyCanonicalEvidence()
            throws Exception {
        var coarseOwner = elementsJson().replace(
                "\"regionIds\":[\"item-a\",\"item-b\"]",
                "\"regionIds\":[\"repeat\"]"
        );
        var currentFailure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseElements(
                        coarseOwner, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy
                                .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_AND_EVIDENCE_OWNER,
                        VisualObservationSemanticPolicy
                                .SLOT_LEAF_EVIDENCE_AND_GROUP_REGION_CARDINALITY_REQUIRED
                )
        );
        assertEquals("VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING",
                currentFailure.diagnosticCode());

        var normalized = codec.parseElements(
                coarseOwner, views(), List.of(IMAGE_ID),
                VisualObservationNormalizationPolicy
                        .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER,
                VisualObservationSemanticPolicy
                        .SLOT_LEAF_EVIDENCE_AND_GROUP_REGION_CARDINALITY_REQUIRED
        );
        assertEquals(List.of("item-a", "item-b"),
                normalized.grounding().regionIdsForElement("item-label"));
        assertEquals(0, normalized.normalizedElementRegionOwners());
        assertEquals(1, normalized.normalizedRepeatedItemSlotOwners());

        var missingSecondItemEvidence = coarseOwner.replace(
                "\"left\":100,\"top\":6300,\"right\":3000,\"bottom\":6800",
                "\"left\":100,\"top\":900,\"right\":3000,\"bottom\":1400"
        );
        var incompleteFailure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseElements(
                        missingSecondItemEvidence, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy
                                .BOUNDED_ENUM_UNIQUE_ITEM_PARENT_EVIDENCE_AND_ITEM_SLOT_OWNER,
                        VisualObservationSemanticPolicy
                                .SLOT_LEAF_EVIDENCE_AND_GROUP_REGION_CARDINALITY_REQUIRED
                )
        );
        assertEquals("VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING",
                incompleteFailure.diagnosticCode());
    }

    @Test
    void classifiesProviderLengthStopsWithoutInspectingOrPersistingPayload() {
        var response = new ProviderInferenceResponse(
                "{\"partial\":true}", "request-1", "qwen3.8-max",
                new ProviderUsage(10, 20), "length"
        );

        assertEquals("VISUAL_GROUNDING_OUTPUT_TRUNCATED", assertThrows(
                InvalidVisualAnalysisException.class,
                () -> LiveInferenceWorker.requireCompleteGroundedResponse(
                        InferenceStage.OBSERVE, response
                )
        ).diagnosticCode());
    }

    @Test
    void classifiesHierarchyShapeAndSupportFailuresWithoutPersistingProviderValues() throws Exception {
        var views = views();
        var observed = codec.parseElements(elementsJson(), views, List.of(IMAGE_ID));

        assertHierarchyDiagnostic(
                hierarchyJson().replace("\"entityId\":\"item\"", "\"entityId\":\"document\""),
                observed, "VISUAL_HIERARCHY_V2_ENTITY_ID_DUPLICATE"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace("\"childEntityId\":\"item\"", "\"childEntityId\":\"missing\""),
                observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_ENDPOINT_INVALID"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace(
                        "\"supportingElementIds\":[\"title\"]",
                        "\"supportingElementIds\":[\"unknown-element\"]"
                ),
                observed, "VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN"
        );
    }

    @Test
    void versionedDerivedCardinalityUsesOnlyUniqueGroupEvidence() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var mismatched = hierarchyJson().replace(
                "\"cardinality\":\"MANY\"", "\"cardinality\":\"ONE\""
        );

        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_CARDINALITY_MISMATCH", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseHierarchy(
                        mismatched, observed.inventory(), observed.grounding()
                )
        ).diagnosticCode());

        var derived = codec.parseHierarchy(
                mismatched, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED
        );
        assertEquals(VisualMultiplicity.MANY,
                derived.hierarchy().relationships().getFirst().cardinality());
        assertEquals(1, derived.derivedRelationshipCardinalities());

        assertDerivedHierarchyDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\",\"title\"]}"
                ), observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_COUNT_INVALID"
        );
        assertDerivedHierarchyDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"unknown-element\"]}"
                ), observed, "VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN"
        );
        assertDerivedHierarchyDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"title\"]}"
                ), observed, "VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP"
        );
    }

    @Test
    void supportIdPolicyNormalizesOnlyExactDuplicatesAndClassifiesOtherListFailures() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var exactDuplicate = hierarchyJson().replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\",\"row-group\"]}"
        );

        var normalized = codec.parseHierarchy(
                exactDuplicate, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES
        );

        assertEquals(List.of("row-group"),
                normalized.hierarchy().relationships().getFirst().supportingElementIds());
        assertEquals(1, normalized.normalizedRelationshipSupportIdReferences());

        assertSupportIdDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":null}"
                ), observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_MISSING"
        );
        assertSupportIdDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[]}"
                ), observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY"
        );
        assertSupportIdDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"Row Group\"]}"
                ), observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_ID_INVALID"
        );
        var tooMany = "\"" + String.join("\",\"",
                java.util.Collections.nCopies(17, "row-group")) + "\"";
        assertSupportIdDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[" + tooMany + "]}"
                ), observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_LIMIT_EXCEEDED"
        );
        assertSupportIdDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\",\"title\"]}"
                ), observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_COUNT_INVALID"
        );
    }

    @Test
    void supportOwnerPolicyNormalizesOnlyOneExactContainerRegionGroupOwner() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var slotSupport = hierarchyJson().replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"item-label\"]}"
        );

        var v21Failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        slotSupport, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", v21Failure.diagnosticCode());

        var normalized = codec.parseHierarchy(
                slotSupport, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy
                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER,
                VisualRelationshipRegionPolicy
                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
        );
        assertEquals(List.of("row-group"),
                normalized.hierarchy().relationships().getFirst().supportingElementIds());
        assertEquals(VisualMultiplicity.MANY,
                normalized.hierarchy().relationships().getFirst().cardinality());
        assertEquals(1, normalized.normalizedRelationshipSupportOwners());
        assertEquals(0, normalized.normalizedRelationshipSupportIdReferences());
        assertEquals(0, normalized.normalizedRelationshipRegions());

        var unknown = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        slotSupport.replace("[\"item-label\"]}", "[\"unknown-element\"]}"),
                        observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN", unknown.diagnosticCode());

        var nonContainer = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        slotSupport.replace("\"regionId\":\"repeat\"", "\"regionId\":\"item-a\""),
                        observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", nonContainer.diagnosticCode());

        var ambiguousObserved = codec.parseElements(
                elementsJson().replace(
                        "{\"elementId\":\"item-label\"",
                        "{\"elementId\":\"second-row-group\",\"kind\":\"GROUP\",\"proposedKey\":\"items2\",\"displayName\":\"第二重复组\",\"multiplicity\":\"MANY\",\"valueHint\":null,\"regionIds\":[\"repeat\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":2000,\"right\":10000,\"bottom\":10000}}]},\n                    {\"elementId\":\"item-label\""
                ), views(), List.of(IMAGE_ID)
        );
        var ambiguous = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        slotSupport, ambiguousObserved.inventory(), ambiguousObserved.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", ambiguous.diagnosticCode());
    }

    @Test
    void emptySupportPolicyUsesOnlyOneExactConnectedRelationshipRegionGroupOwner()
            throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var emptySupport = hierarchyJson().replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                "\"regionId\":\"repeat\",\"supportingElementIds\":[]}"
        );

        var v31Failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        emptySupport, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_OR_SOURCE_ANCESTOR_CONNECTED_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY",
                v31Failure.diagnosticCode());

        var normalized = codec.parseHierarchy(
                emptySupport, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy
                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_SUPPORT,
                VisualRelationshipRegionPolicy
                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
        );
        assertEquals(List.of("row-group"),
                normalized.hierarchy().relationships().getFirst().supportingElementIds());
        assertEquals(VisualMultiplicity.MANY,
                normalized.hierarchy().relationships().getFirst().cardinality());
        assertEquals(1, normalized.normalizedRelationshipSupportOwners());
        assertEquals(1, normalized.normalizedRelationshipEmptySupportOwners());
        assertEquals(0, normalized.normalizedRelationshipEnclosingSupportOwners());
        assertEquals(0, normalized.normalizedRelationshipSourceAncestorSupportOwners());
        assertEquals(0, normalized.normalizedRelationshipRegions());

        var ambiguousObserved = codec.parseElements(
                elementsJson().replace(
                        "{\"elementId\":\"item-label\"",
                        "{\"elementId\":\"second-row-group\",\"kind\":\"GROUP\",\"proposedKey\":\"items2\",\"displayName\":\"第二重复组\",\"multiplicity\":\"MANY\",\"valueHint\":null,\"regionIds\":[\"repeat\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":2000,\"right\":10000,\"bottom\":10000}}]},\n                    {\"elementId\":\"item-label\""
                ), views(), List.of(IMAGE_ID)
        );
        assertEmptySupportDiagnostic(emptySupport, ambiguousObserved);

        var nonContainer = emptySupport.replace("\"regionId\":\"repeat\"",
                "\"regionId\":\"header\"");
        assertEmptySupportDiagnostic(nonContainer, observed);

        var disconnected = emptySupport.replace(
                "\"entityId\":\"item\",\"schemaKey\":\"item\",\"displayName\":\"项目\",\"regionIds\":[\"item-a\",\"item-b\"]",
                "\"entityId\":\"item\",\"schemaKey\":\"item\",\"displayName\":\"项目\",\"regionIds\":[\"header\"]"
        );
        assertEmptySupportDiagnostic(disconnected, observed);

        var missingSupport = emptySupport.replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[]}",
                "\"regionId\":\"repeat\",\"supportingElementIds\":null}"
        );
        var missing = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        missingSupport, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_SUPPORT,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_MISSING",
                missing.diagnosticCode());
    }

    @Test
    void unknownSupportPolicyUsesOnlyOneExactConnectedRelationshipRegionGroupOwner()
            throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var unknownSupport = hierarchyJson().replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"unknown-element\"]}"
        );

        var v32Failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        unknownSupport, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_SUPPORT,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN",
                v32Failure.diagnosticCode());

        var normalized = codec.parseHierarchy(
                unknownSupport, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy
                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT,
                VisualRelationshipRegionPolicy
                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
        );
        assertEquals(List.of("row-group"),
                normalized.hierarchy().relationships().getFirst().supportingElementIds());
        assertEquals(VisualMultiplicity.MANY,
                normalized.hierarchy().relationships().getFirst().cardinality());
        assertEquals(1, normalized.normalizedRelationshipSupportOwners());
        assertEquals(0, normalized.normalizedRelationshipEmptySupportOwners());
        assertEquals(1, normalized.normalizedRelationshipUnknownSupportOwners());
        assertEquals(0, normalized.normalizedRelationshipRegions());

        var ambiguousObserved = codec.parseElements(
                elementsJson().replace(
                        "{\"elementId\":\"item-label\"",
                        "{\"elementId\":\"second-row-group\",\"kind\":\"GROUP\",\"proposedKey\":\"items2\",\"displayName\":\"第二重复组\",\"multiplicity\":\"MANY\",\"valueHint\":null,\"regionIds\":[\"repeat\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":2000,\"right\":10000,\"bottom\":10000}}]},\n                    {\"elementId\":\"item-label\""
                ), views(), List.of(IMAGE_ID)
        );
        assertUnknownSupportDiagnostic(unknownSupport, ambiguousObserved);
        assertUnknownSupportDiagnostic(
                unknownSupport.replace("\"regionId\":\"repeat\"", "\"regionId\":\"header\""),
                observed
        );
        assertUnknownSupportDiagnostic(
                unknownSupport.replace(
                        "\"entityId\":\"item\",\"schemaKey\":\"item\",\"displayName\":\"项目\",\"regionIds\":[\"item-a\",\"item-b\"]",
                        "\"entityId\":\"item\",\"schemaKey\":\"item\",\"displayName\":\"项目\",\"regionIds\":[\"header\"]"
                ),
                observed
        );
        assertEquals("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_COUNT_INVALID",
                assertThrows(InvalidVisualAnalysisException.class, () ->
                        codec.parseHierarchy(
                                unknownSupport.replace(
                                        "[\"unknown-element\"]}",
                                        "[\"unknown-element\",\"second-unknown\"]}"
                                ),
                                observed.inventory(), observed.grounding(),
                                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                                VisualHierarchyPrerequisitePolicy
                                        .RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                                VisualRelationshipSupportIdPolicy
                                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT,
                                VisualRelationshipRegionPolicy
                                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                        )).diagnosticCode());
    }

    @Test
    void enclosingSupportOwnerPolicyBreaksOnlyOneEvidenceBoundedSupportRegionDeadlock()
            throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var wrongRegionSlotSupport = hierarchyJson().replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                "\"regionId\":\"item-a\",\"supportingElementIds\":[\"item-label\"]}"
        );

        var exactRegionOnly = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        wrongRegionSlotSupport, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_REGION_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", exactRegionOnly.diagnosticCode());

        var normalized = codec.parseHierarchy(
                wrongRegionSlotSupport, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy
                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_CONNECTED_GROUP_OWNER,
                VisualRelationshipRegionPolicy
                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
        );
        assertEquals(List.of("row-group"),
                normalized.hierarchy().relationships().getFirst().supportingElementIds());
        assertEquals(VisualMultiplicity.MANY,
                normalized.hierarchy().relationships().getFirst().cardinality());
        assertEquals("repeat", normalized.entityRegions().relationships().getFirst().regionId());
        assertEquals(1, normalized.normalizedRelationshipSupportOwners());
        assertEquals(1, normalized.normalizedRelationshipEnclosingSupportOwners());
        assertEquals(1, normalized.normalizedRelationshipRegions());

        var ambiguousObserved = codec.parseElements(
                elementsJson().replace(
                        "{\"elementId\":\"item-label\"",
                        "{\"elementId\":\"second-row-group\",\"kind\":\"GROUP\",\"proposedKey\":\"items2\",\"displayName\":\"第二重复组\",\"multiplicity\":\"MANY\",\"valueHint\":null,\"regionIds\":[\"repeat\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":2000,\"right\":10000,\"bottom\":10000}}]},\n                    {\"elementId\":\"item-label\""
                ), views(), List.of(IMAGE_ID)
        );
        var ambiguous = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        wrongRegionSlotSupport, ambiguousObserved.inventory(),
                        ambiguousObserved.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_CONNECTED_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", ambiguous.diagnosticCode());
    }

    @Test
    void sourceAncestorSupportOwnerPolicyUsesOnlyOneValidatedRegionAncestor() throws Exception {
        var wideSupportJson = elementsJson().replace(
                "\"regionIds\":[\"item-a\",\"item-b\"]",
                "\"regionIds\":[\"item-a\",\"item-b\",\"root\"]"
        );
        var observed = codec.parseElements(wideSupportJson, views(), List.of(IMAGE_ID));
        var itemRegionSlotSupport = hierarchyJson().replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                "\"regionId\":\"item-a\",\"supportingElementIds\":[\"item-label\"]}"
        );

        var enclosingOnly = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        itemRegionSlotSupport, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_CONNECTED_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", enclosingOnly.diagnosticCode());

        var normalized = codec.parseHierarchy(
                itemRegionSlotSupport, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy
                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_OR_SOURCE_ANCESTOR_CONNECTED_GROUP_OWNER,
                VisualRelationshipRegionPolicy
                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
        );
        assertEquals(List.of("row-group"),
                normalized.hierarchy().relationships().getFirst().supportingElementIds());
        assertEquals(VisualMultiplicity.MANY,
                normalized.hierarchy().relationships().getFirst().cardinality());
        assertEquals("repeat", normalized.entityRegions().relationships().getFirst().regionId());
        assertEquals(1, normalized.normalizedRelationshipSupportOwners());
        assertEquals(0, normalized.normalizedRelationshipEnclosingSupportOwners());
        assertEquals(1, normalized.normalizedRelationshipSourceAncestorSupportOwners());
        assertEquals(1, normalized.normalizedRelationshipRegions());

        var ambiguousObserved = codec.parseElements(
                wideSupportJson.replace(
                        "{\"elementId\":\"item-label\"",
                        "{\"elementId\":\"second-row-group\",\"kind\":\"GROUP\",\"proposedKey\":\"items2\",\"displayName\":\"第二重复组\",\"multiplicity\":\"MANY\",\"valueHint\":null,\"regionIds\":[\"repeat\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":2000,\"right\":10000,\"bottom\":10000}}]},\n                    {\"elementId\":\"item-label\""
                ), views(), List.of(IMAGE_ID)
        );
        var ambiguous = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        itemRegionSlotSupport, ambiguousObserved.inventory(),
                        ambiguousObserved.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy
                                .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_ENCLOSING_OR_SOURCE_ANCESTOR_CONNECTED_GROUP_OWNER,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP", ambiguous.diagnosticCode());
    }

    @Test
    void relationshipRegionPolicyNormalizesOnlyUniqueCardinalityCompatibleGroupOwnership() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var wrongSingularRegion = hierarchyJson().replace(
                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                "\"regionId\":\"root\",\"supportingElementIds\":[\"row-group\"]}"
        );

        assertDetailedRegionDiagnostic(
                wrongSingularRegion, observed,
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID"
        );

        var normalized = codec.parseHierarchy(
                wrongSingularRegion, observed.inventory(), observed.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                VisualRelationshipRegionPolicy.UNIQUE_CARDINALITY_COMPATIBLE_GROUP_REGION
        );

        assertEquals("repeat", normalized.entityRegions().relationships().getFirst().regionId());
        assertEquals(1, normalized.normalizedRelationshipRegions());
        assertEquals(0, normalized.normalizedRelationshipSupportIdReferences());

        var ambiguousObservation = codec.parseElements(
                ambiguousRelationshipRegionElementsJson(), views(), List.of(IMAGE_ID)
        );
        var ambiguous = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        hierarchyJson().replace(
                                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                                "\"regionId\":\"repeat\",\"supportingElementIds\":[\"owner-group\"]}"
                        ), ambiguousObservation.inventory(), ambiguousObservation.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                        VisualRelationshipRegionPolicy.UNIQUE_CARDINALITY_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals(
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID",
                ambiguous.diagnosticCode()
        );

        var incompatibleObservation = codec.parseElements(
                relationshipRegionElementsJson("owner", 1200, 3600).replace(
                        "\"elementId\":\"owner-group\",\"kind\":\"GROUP\",\"proposedKey\":\"owner\",\"displayName\":\"容器\",\"multiplicity\":\"ONE\"",
                        "\"elementId\":\"owner-group\",\"kind\":\"GROUP\",\"proposedKey\":\"owner\",\"displayName\":\"容器\",\"multiplicity\":\"MANY\""
                ), views(), List.of(IMAGE_ID)
        );
        var missingSource = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        relationshipRegionHierarchyJson(), incompatibleObservation.inventory(),
                        incompatibleObservation.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                        VisualRelationshipRegionPolicy.UNIQUE_CARDINALITY_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals("VISUAL_SEMANTIC_GROUP_REGION_INVALID", missingSource.diagnosticCode());
        assertEquals(InferenceStage.OBSERVE, missingSource.earliestStage().orElseThrow());
    }

    @Test
    void connectionAwareRegionPolicyNormalizesOnlyOneCompatibleGroupOwnedRegion() throws Exception {
        var multiRegionObservation = codec.parseElements(
                relationshipRegionElementsJson("owner", 1200, 3600).replace(
                        "\"elementId\":\"owner-group\",\"kind\":\"GROUP\",\"proposedKey\":\"owner\",\"displayName\":\"容器\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"owner\"]",
                        "\"elementId\":\"owner-group\",\"kind\":\"GROUP\",\"proposedKey\":\"owner\",\"displayName\":\"容器\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"owner\",\"orphan\"]"
                ), views(), List.of(IMAGE_ID)
        );
        var disconnected = relationshipRegionHierarchyJson().replace(
                "\"cardinality\":\"ONE\",\"regionId\":\"orphan\",\"supportingElementIds\":[\"owner-group\"]",
                "\"cardinality\":\"ONE\",\"regionId\":\"owner\",\"supportingElementIds\":[\"owner-group\"]"
        );

        var v20Failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        disconnected, multiRegionObservation.inventory(), multiRegionObservation.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                        VisualRelationshipRegionPolicy.UNIQUE_CARDINALITY_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals(
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID",
                v20Failure.diagnosticCode()
        );

        var normalized = codec.parseHierarchy(
                disconnected, multiRegionObservation.inventory(), multiRegionObservation.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                VisualRelationshipRegionPolicy
                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
        );
        assertEquals("orphan", normalized.entityRegions().relationships().getFirst().regionId());
        assertEquals(1, normalized.normalizedRelationshipRegions());

        var singleDisconnectedObservation = codec.parseElements(
                relationshipRegionElementsJson("owner", 1200, 3600), views(), List.of(IMAGE_ID)
        );
        var noCompatibleRegion = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        disconnected, singleDisconnectedObservation.inventory(),
                        singleDisconnectedObservation.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals(
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID",
                noCompatibleRegion.diagnosticCode()
        );

        var ambiguousObservationJson = relationshipRegionElementsJson("owner", 1200, 3600)
                .replace(
                        "{\"regionId\":\"orphan\",\"parentRegionId\":\"root\"",
                        "{\"regionId\":\"owner-inner\",\"parentRegionId\":\"owner\",\"kind\":\"GROUP\",\"multiplicity\":\"ONE\",\"readingOrder\":0,\"repeatGroupId\":null,\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":500,\"top\":1000,\"right\":9500,\"bottom\":4000}}]},\n                    {\"regionId\":\"orphan\",\"parentRegionId\":\"root\""
                )
                .replace(
                        "\"elementId\":\"owner-group\",\"kind\":\"GROUP\",\"proposedKey\":\"owner\",\"displayName\":\"容器\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"owner\"]",
                        "\"elementId\":\"owner-group\",\"kind\":\"GROUP\",\"proposedKey\":\"owner\",\"displayName\":\"容器\",\"multiplicity\":\"ONE\",\"valueHint\":null,\"regionIds\":[\"owner\",\"owner-inner\"]"
                );
        var ambiguousObservation = codec.parseElements(
                ambiguousObservationJson, views(), List.of(IMAGE_ID)
        );
        var ambiguousHierarchy = relationshipRegionHierarchyJson().replace(
                "\"entityId\":\"child\",\"schemaKey\":\"child\",\"displayName\":\"子项\",\"regionIds\":[\"orphan\"]",
                "\"entityId\":\"child\",\"schemaKey\":\"child\",\"displayName\":\"子项\",\"regionIds\":[\"owner-inner\"]"
        );
        var ambiguous = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        ambiguousHierarchy, ambiguousObservation.inventory(), ambiguousObservation.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                        VisualRelationshipRegionPolicy
                                .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                )
        );
        assertEquals(
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID",
                ambiguous.diagnosticCode()
        );
    }

    @Test
    void classifiesHierarchyEntityAndRelationshipFieldsWithoutPersistingProviderValues() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));

        assertHierarchyDiagnostic(
                hierarchyJson().replace("\"entityId\":\"item\"", "\"entityId\":\"Item\""),
                observed, "VISUAL_HIERARCHY_V2_ENTITY_ID_INVALID"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace("\"schemaKey\":\"item\"", "\"schemaKey\":\"system-item\""),
                observed, "VISUAL_HIERARCHY_V2_ENTITY_SCHEMA_KEY_INVALID"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace("\"displayName\":\"项目\"", "\"displayName\":\"\""),
                observed, "VISUAL_HIERARCHY_V2_ENTITY_DISPLAY_NAME_INVALID"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace(
                        "\"supportingElementIds\":[\"title\"]",
                        "\"supportingElementIds\":[\"title\",\"title\"]"
                ),
                observed, "VISUAL_HIERARCHY_V2_ENTITY_SUPPORT_IDS_INVALID"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace(
                        "\"relationshipId\":\"document-items\"",
                        "\"relationshipId\":\"Document Items\""
                ),
                observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_ID_INVALID"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace("\"fieldKey\":\"items\"", "\"fieldKey\":\"\""),
                observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_FIELD_KEY_INVALID"
        );
        assertHierarchyDiagnostic(
                hierarchyJson().replace(
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\"]}",
                        "\"regionId\":\"repeat\",\"supportingElementIds\":[\"row-group\",\"row-group\"]}"
                ),
                observed, "VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_INVALID"
        );
    }

    @Test
    void routesObservationSemanticOmissionsBackToObservation() throws Exception {
        var flattened = elementsJson().replace(
                "\"kind\":\"GROUP\",\"proposedKey\":\"items\",\"displayName\":\"重复项目\",\"multiplicity\":\"MANY\",\"valueHint\":null",
                "\"kind\":\"SLOT\",\"proposedKey\":\"items\",\"displayName\":\"重复项目\",\"multiplicity\":\"MANY\",\"valueHint\":\"TEXT\""
        );

        assertDiagnostic(
                flattened, views(), "VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING"
        );
        assertDiagnostic(
                elementsJson().replace(
                        "\"elementId\":\"row-group\",\"kind\":\"GROUP\",\"proposedKey\":\"items\",\"displayName\":\"重复项目\",\"multiplicity\":\"MANY\"",
                        "\"elementId\":\"row-group\",\"kind\":\"GROUP\",\"proposedKey\":\"items\",\"displayName\":\"重复项目\",\"multiplicity\":\"ONE\""
                ),
                views(), "VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID"
        );
        assertDiagnostic(
                elementsJson().replace(
                        "\"regionIds\":[\"item-a\",\"item-b\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":100,\"top\":2300,\"right\":3000,\"bottom\":2800}},{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":100,\"top\":6300,\"right\":3000,\"bottom\":6800}}]",
                        "\"regionIds\":[\"header\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":100,\"top\":100,\"right\":3000,\"bottom\":700}}]"
                ),
                views(), "VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING"
        );
    }

    @Test
    void rejectsContainerSizedSlotEvidenceOnlyUnderTheLeafEvidencePolicy() throws Exception {
        var containerSlot = elementsJson().replace(
                "\"regionIds\":[\"header\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":100,\"top\":100,\"right\":3000,\"bottom\":700}}]}",
                "\"regionIds\":[\"root\"],\"evidence\":[{\"viewId\":\"view-00-overview-00\",\"boundingBox\":{\"left\":0,\"top\":0,\"right\":10000,\"bottom\":10000}}]}"
        );

        codec.parseElements(containerSlot, views(), List.of(IMAGE_ID));
        var failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseElements(
                        containerSlot, views(), List.of(IMAGE_ID),
                        VisualObservationNormalizationPolicy.STRICT,
                        VisualObservationSemanticPolicy.SLOT_LEAF_EVIDENCE_REQUIRED
                )
        );

        assertEquals("VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT", failure.diagnosticCode());
    }

    @Test
    void routesHierarchyAndBindingSemanticIssuesToTheirEarliestStage() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));

        assertHierarchyDiagnostic(
                hierarchyWithoutGroupEdgeJson(), observed,
                "VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_MISSING"
        );

        var hierarchy = codec.parseHierarchy(
                hierarchyJson(), observed.inventory(), observed.grounding()
        );
        assertEquals("VISUAL_SEMANTIC_BINDING_NOT_NEAREST_ENTITY", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseBindings(
                        bindingsJson().replace(
                                "{\"elementId\":\"item-label\",\"entityId\":\"item\"}",
                                "{\"elementId\":\"item-label\",\"entityId\":\"document\"}"
                        ), observed.inventory(), hierarchy.hierarchy(), observed.grounding(),
                        hierarchy.entityRegions()
                )
        ).diagnosticCode());
    }

    @Test
    void minimalEntityRegionPolicyIsOptInAtTheHierarchyContractBoundary() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var redundant = hierarchyJson().replace(
                "\"regionIds\":[\"item-a\",\"item-b\"]",
                "\"regionIds\":[\"root\",\"item-a\"]"
        );

        codec.parseHierarchy(redundant, observed.inventory(), observed.grounding());
        var failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        redundant, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES,
                        VisualRelationshipRegionPolicy.STRICT,
                        VisualHierarchySemanticPolicy.MINIMAL_ENTITY_REGION_OWNERSHIP
                )
        );

        assertEquals("VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT",
                failure.diagnosticCode());
        assertEquals(InferenceStage.HIERARCHY, failure.earliestStage().orElseThrow());
    }

    @Test
    void uniqueMinimalBindingPolicyRejectsEqualOwnersAtTheContractBoundary() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var hierarchy = new VisualHierarchyPlan(
                VisualHierarchyPlan.VERSION_V2,
                "document",
                List.of(
                        new VisualEntityPlan("document", "document", "Document", List.of("title")),
                        new VisualEntityPlan("item-a-entity", "item-a-entity", "Item A", List.of("row-group")),
                        new VisualEntityPlan("item-b-entity", "item-b-entity", "Item B", List.of("row-group"))
                ),
                List.of(
                        new VisualRelationshipPlan(
                                "document-item-a", "document", "item-a-entity", "item-a",
                                "Item A", VisualMultiplicity.MANY, List.of("row-group")
                        ),
                        new VisualRelationshipPlan(
                                "document-item-b", "document", "item-b-entity", "item-b",
                                "Item B", VisualMultiplicity.MANY, List.of("row-group")
                        )
                )
        );
        var entityRegions = new VisualEntityRegionPlan(
                VisualEntityRegionPlan.VERSION,
                List.of(
                        new VisualEntityRegionOwnership("document", List.of("root")),
                        new VisualEntityRegionOwnership(
                                "item-a-entity", List.of("item-a", "item-b")
                        ),
                        new VisualEntityRegionOwnership(
                                "item-b-entity", List.of("item-a", "item-b")
                        )
                ),
                List.of()
        );
        var ambiguousBindings = bindingsJson().replace(
                "\"entityId\":\"item\"", "\"entityId\":\"item-a-entity\""
        );

        codec.parseBindings(
                ambiguousBindings, observed.inventory(), hierarchy, observed.grounding(),
                entityRegions
        );
        var failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseBindings(
                        ambiguousBindings, observed.inventory(), hierarchy, observed.grounding(),
                        entityRegions, VisualBindingSemanticPolicy.UNIQUE_MINIMAL_ENTITY_OWNER
                )
        );

        assertEquals("VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS",
                failure.diagnosticCode());
        assertEquals(InferenceStage.HIERARCHY, failure.earliestStage().orElseThrow());
    }

    @Test
    void routesRelationshipWithoutAnyObservedGroupBackToObservation() throws Exception {
        var observed = codec.parseElements(flatElementsJson(), views(), List.of(IMAGE_ID));

        var failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        hierarchyJson(), observed.inventory(), observed.grounding()
                )
        );
        assertEquals("VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING",
                failure.diagnosticCode());
        assertEquals(InferenceStage.OBSERVE, failure.earliestStage().orElseThrow());

        var flat = codec.parseHierarchy(
                flatHierarchyJson(), observed.inventory(), observed.grounding()
        );
        assertEquals(List.of(), flat.hierarchy().relationships());
    }

    @Test
    void relationshipRegionOwnerPolicyRewindsOnlyEvidenceBackedGroupOmissions() throws Exception {
        var omitted = codec.parseElements(
                relationshipRegionElementsJson("owner", 1200, 3600), views(), List.of(IMAGE_ID)
        );

        var legacyFailure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        relationshipRegionHierarchyJson(), omitted.inventory(), omitted.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED
                )
        );
        assertEquals("VISUAL_SEMANTIC_HIERARCHY_EDGE_REGION_INVALID",
                legacyFailure.diagnosticCode());
        assertEquals(InferenceStage.HIERARCHY, legacyFailure.earliestStage().orElseThrow());

        var rewind = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        relationshipRegionHierarchyJson(), omitted.inventory(), omitted.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED
                )
        );
        assertEquals("VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING",
                rewind.diagnosticCode());
        assertEquals(InferenceStage.OBSERVE, rewind.earliestStage().orElseThrow());

        var repaired = codec.parseElements(
                relationshipRegionElementsJson("orphan", 5200, 8600), views(), List.of(IMAGE_ID)
        );
        var accepted = codec.parseHierarchy(
                relationshipRegionHierarchyJson(), repaired.inventory(), repaired.grounding(),
                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED
        );
        assertEquals(1, accepted.hierarchy().relationships().size());
    }

    @Test
    void relationshipRegionOwnerPolicyKeepsOwnedGroupReuseAtHierarchy() throws Exception {
        var observed = codec.parseElements(
                relationshipRegionElementsJson("owner", 1200, 3600), views(), List.of(IMAGE_ID)
        );

        var failure = assertThrows(InvalidVisualAnalysisException.class, () ->
                codec.parseHierarchy(
                        reusedRelationshipGroupHierarchyJson(),
                        observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED
                )
        );
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_GROUP_REUSED", failure.diagnosticCode());
        assertTrue(failure.earliestStage().isEmpty());
    }

    @Test
    void keepsV17RegionDiagnosticsStableAndClassifiesV18RepairCauses() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var disconnected = hierarchyJson().replace(
                "\"regionIds\":[\"item-a\",\"item-b\"]",
                "\"regionIds\":[\"header\"]"
        );

        assertEquals("VISUAL_HIERARCHY_V2_REGION_OWNERSHIP_INVALID", assertThrows(
                InvalidVisualAnalysisException.class, () -> codec.parseHierarchy(
                        disconnected, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED
                )
        ).diagnosticCode());
        assertDetailedRegionDiagnostic(
                hierarchyJson().replace("\"regionIds\":[\"root\"]", "\"regionIds\":[]"),
                observed, VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                "VISUAL_HIERARCHY_V2_ENTITY_REGION_IDS_INVALID"
        );
        assertDetailedRegionDiagnostic(
                hierarchyJson().replace("\"regionId\":\"repeat\"", "\"regionId\":\"Repeat\""),
                observed, VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_ID_INVALID"
        );
        assertDetailedRegionDiagnostic(
                hierarchyJson().replace(
                        "\"regionIds\":[\"item-a\",\"item-b\"]",
                        "\"regionIds\":[\"unknown\"]"
                ),
                observed, VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                "VISUAL_HIERARCHY_V2_REGION_REFERENCE_UNKNOWN"
        );
        assertDetailedRegionDiagnostic(
                hierarchyJson().replace("\"regionIds\":[\"root\"]", "\"regionIds\":[\"header\"]"),
                observed, VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                "VISUAL_HIERARCHY_V2_ROOT_REGION_OWNERSHIP_INVALID"
        );
        assertDetailedRegionDiagnostic(
                disconnected, observed, VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID"
        );
        assertDetailedRegionDiagnostic(
                hierarchyJson().replace("\"cardinality\":\"MANY\"", "\"cardinality\":\"ONE\""),
                observed, VisualRelationshipCardinalityPolicy.MODEL_ASSERTED,
                "VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID"
        );
    }

    @Test
    void derivesStageLocalCropsOnlyFromTheVerifiedPlan() throws Exception {
        var observed = codec.parseElements(elementsJson(), views(), List.of(IMAGE_ID));
        var hierarchy = codec.parseHierarchy(
                hierarchyJson(), observed.inventory(), observed.grounding()
        );
        var selector = new VisualRepairCropSelector();

        assertEquals(List.of(), selector.select(
                InferenceStage.HIERARCHY, List.of(), List.of(IMAGE_ID),
                observed.inventory(), observed.grounding(), hierarchy.hierarchy(),
                hierarchy.entityRegions()
        ));
        assertEquals(List.of(), selector.select(
                InferenceStage.HIERARCHY,
                List.of("VISUAL_HIERARCHY_V2_ENTITY_ID_INVALID"), List.of(IMAGE_ID),
                observed.inventory(), observed.grounding(), hierarchy.hierarchy(),
                hierarchy.entityRegions()
        ));
        assertEquals(List.of(), selector.select(
                InferenceStage.HIERARCHY,
                List.of("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY"), List.of(IMAGE_ID),
                observed.inventory(), observed.grounding(), hierarchy.hierarchy(),
                hierarchy.entityRegions()
        ));
        assertEquals(List.of(new VisualTargetCrop(
                0, new CandidateBoundingBox(0, 2000, 10_000, 10_000)
        )), selector.select(
                InferenceStage.HIERARCHY,
                List.of("VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_MISSING"), List.of(IMAGE_ID),
                observed.inventory(), observed.grounding(), hierarchy.hierarchy(),
                hierarchy.entityRegions()
        ));
        assertEquals(List.of(
                new VisualTargetCrop(0, new CandidateBoundingBox(0, 0, 10_000, 2000)),
                new VisualTargetCrop(0, new CandidateBoundingBox(0, 2000, 10_000, 6000)),
                new VisualTargetCrop(0, new CandidateBoundingBox(0, 6000, 10_000, 10_000))
        ), selector.select(
                InferenceStage.ELEMENT_BINDING,
                List.of("VISUAL_SEMANTIC_BINDING_NOT_NEAREST_ENTITY"), List.of(IMAGE_ID),
                observed.inventory(), observed.grounding(), hierarchy.hierarchy(),
                hierarchy.entityRegions()
        ));
    }

    private void assertDiagnostic(String json, VisualViewPlan views, String expectedCode) {
        assertEquals(expectedCode, assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseElements(json, views, List.of(IMAGE_ID))).diagnosticCode());
    }

    private void assertHierarchyDiagnostic(
            String json,
            GroundedElementInventory observed,
            String expectedCode
    ) {
        assertEquals(expectedCode, assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseHierarchy(
                        json, observed.inventory(), observed.grounding()
                )).diagnosticCode());
    }

    private void assertDerivedHierarchyDiagnostic(
            String json,
            GroundedElementInventory observed,
            String expectedCode
    ) {
        assertEquals(expectedCode, assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseHierarchy(
                        json, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED
                )).diagnosticCode());
    }

    private void assertDetailedRegionDiagnostic(
            String json,
            GroundedElementInventory observed,
            VisualRelationshipCardinalityPolicy cardinalityPolicy,
            String expectedCode
    ) {
        assertEquals(expectedCode, assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseHierarchy(
                        json, observed.inventory(), observed.grounding(), cardinalityPolicy,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES
                )).diagnosticCode());
    }

    private void assertSupportIdDiagnostic(
            String json,
            GroundedElementInventory observed,
            String expectedCode
    ) {
        assertEquals(expectedCode, assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseHierarchy(
                        json, observed.inventory(), observed.grounding(),
                        VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                        VisualHierarchyPrerequisitePolicy.RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                        VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                        VisualRelationshipSupportIdPolicy.CANONICALIZE_EXACT_DUPLICATES
                )).diagnosticCode());
    }

    private void assertEmptySupportDiagnostic(
            String json,
            GroundedElementInventory observed
    ) {
        assertEquals("VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY",
                assertThrows(InvalidVisualAnalysisException.class, () ->
                        codec.parseHierarchy(
                                json, observed.inventory(), observed.grounding(),
                                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                                VisualHierarchyPrerequisitePolicy
                                        .RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                                VisualRelationshipSupportIdPolicy
                                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_SUPPORT,
                                VisualRelationshipRegionPolicy
                                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                        )).diagnosticCode());
    }

    private void assertUnknownSupportDiagnostic(
            String json,
            GroundedElementInventory observed
    ) {
        assertEquals("VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN",
                assertThrows(InvalidVisualAnalysisException.class, () ->
                        codec.parseHierarchy(
                                json, observed.inventory(), observed.grounding(),
                                VisualRelationshipCardinalityPolicy.SUPPORT_GROUP_DERIVED,
                                VisualHierarchyPrerequisitePolicy
                                        .RELATIONSHIP_REGION_GROUP_OWNER_REQUIRED,
                                VisualHierarchyRegionDiagnosticPolicy.DETAILED_FIXED_CODES,
                                VisualRelationshipSupportIdPolicy
                                        .CANONICALIZE_EXACT_DUPLICATES_AND_UNIQUE_CONNECTED_GROUP_OWNER_WITH_EMPTY_OR_UNKNOWN_SUPPORT,
                                VisualRelationshipRegionPolicy
                                        .UNIQUE_CARDINALITY_AND_CONNECTION_COMPATIBLE_GROUP_REGION
                        )).diagnosticCode());
    }

    private static VisualViewPlan views() throws Exception {
        return new MultiScaleVisualViewPlanner().plan(
                List.of(new VisualSourceImage(IMAGE_ID, png(), 1_000, 1_000)), List.of()
        );
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1_000, 1_000);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(20, 20, 960, 20);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static String elementsJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-grounding/2.0",
                  "regions":[
                    {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                    {"regionId":"header","parentRegionId":"root","kind":"SECTION","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":2000}}]},
                    {"regionId":"repeat","parentRegionId":"root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                    {"regionId":"item-a","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":6000}}]},
                    {"regionId":"item-b","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":6000,"right":10000,"bottom":10000}}]}
                  ],
                  "elements":[
                    {"elementId":"title","kind":"SLOT","proposedKey":"title","displayName":"标题","multiplicity":"ONE","valueHint":"TEXT","regionIds":["header"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":3000,"bottom":700}}]},
                    {"elementId":"row-group","kind":"GROUP","proposedKey":"items","displayName":"重复项目","multiplicity":"MANY","valueHint":null,"regionIds":["repeat"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                    {"elementId":"item-label","kind":"SLOT","proposedKey":"label","displayName":"项目名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["item-a","item-b"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":2300,"right":3000,"bottom":2800}},{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":6300,"right":3000,"bottom":6800}}]}
                  ]
                }
                """;
    }

    private static String flatElementsJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-grounding/2.0",
                  "regions":[
                    {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                    {"regionId":"content","parentRegionId":"root","kind":"SECTION","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]}
                  ],
                  "elements":[
                    {"elementId":"title","kind":"SLOT","proposedKey":"title","displayName":"标题","multiplicity":"ONE","valueHint":"TEXT","regionIds":["content"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":3000,"bottom":700}}]},
                    {"elementId":"item-label","kind":"SLOT","proposedKey":"label","displayName":"项目名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["content"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":2300,"right":3000,"bottom":2800}}]}
                  ]
                }
                """;
    }

    private static String ambiguousRelationshipRegionElementsJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-grounding/2.0",
                  "regions":[
                    {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                    {"regionId":"header","parentRegionId":"root","kind":"GROUP","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":2000}}]},
                    {"regionId":"header-inner","parentRegionId":"header","kind":"GROUP","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":9900,"bottom":1900}}]},
                    {"regionId":"repeat","parentRegionId":"root","kind":"REPEATED_GROUP","multiplicity":"MANY","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                    {"regionId":"item-a","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":0,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":6000}}]},
                    {"regionId":"item-b","parentRegionId":"repeat","kind":"ITEM","multiplicity":"ONE","readingOrder":1,"repeatGroupId":"rows","evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":6000,"right":10000,"bottom":10000}}]}
                  ],
                  "elements":[
                    {"elementId":"title","kind":"SLOT","proposedKey":"title","displayName":"标题","multiplicity":"ONE","valueHint":"TEXT","regionIds":["header"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":200,"top":200,"right":3000,"bottom":700}}]},
                    {"elementId":"owner-group","kind":"GROUP","proposedKey":"owner","displayName":"容器","multiplicity":"ONE","valueHint":null,"regionIds":["header","header-inner"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":300,"top":300,"right":3000,"bottom":800}}]},
                    {"elementId":"row-group","kind":"GROUP","proposedKey":"items","displayName":"重复项目","multiplicity":"MANY","valueHint":null,"regionIds":["repeat"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":2000,"right":10000,"bottom":10000}}]},
                    {"elementId":"item-label","kind":"SLOT","proposedKey":"label","displayName":"项目名称","multiplicity":"ONE","valueHint":"TEXT","regionIds":["item-a","item-b"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":2300,"right":3000,"bottom":2800}},{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":6300,"right":3000,"bottom":6800}}]}
                  ]
                }
                """;
    }

    private static String relationshipRegionElementsJson(
            String groupRegionId,
            int groupEvidenceTop,
            int groupEvidenceBottom
    ) {
        return """
                {
                  "contractVersion":"renderweave-visual-grounding/2.0",
                  "regions":[
                    {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                    {"regionId":"owner","parentRegionId":"root","kind":"GROUP","multiplicity":"ONE","readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":1000,"right":9500,"bottom":4000}}]},
                    {"regionId":"orphan","parentRegionId":"root","kind":"GROUP","multiplicity":"ONE","readingOrder":1,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":500,"top":5000,"right":9500,"bottom":9000}}]}
                  ],
                  "elements":[
                    {"elementId":"title","kind":"SLOT","proposedKey":"title","displayName":"标题","multiplicity":"ONE","valueHint":"TEXT","regionIds":["root"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":100,"top":100,"right":3000,"bottom":700}}]},
                    {"elementId":"owner-group","kind":"GROUP","proposedKey":"owner","displayName":"容器","multiplicity":"ONE","valueHint":null,"regionIds":["%s"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":1000,"top":%d,"right":9000,"bottom":%d}}]},
                    {"elementId":"orphan-label","kind":"SLOT","proposedKey":"label","displayName":"标签","multiplicity":"ONE","valueHint":"TEXT","regionIds":["orphan"],"evidence":[{"viewId":"view-00-overview-00","boundingBox":{"left":1000,"top":5500,"right":4000,"bottom":6200}}]}
                  ]
                }
                """.formatted(groupRegionId, groupEvidenceTop, groupEvidenceBottom);
    }

    private static String hierarchyJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-hierarchy/2.0",
                  "rootEntityId":"document",
                  "entities":[
                    {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["root"],"supportingElementIds":["title"]},
                    {"entityId":"item","schemaKey":"item","displayName":"项目","regionIds":["item-a","item-b"],"supportingElementIds":["row-group"]}
                  ],
                  "relationships":[
                    {"relationshipId":"document-items","parentEntityId":"document","childEntityId":"item","fieldKey":"items","displayName":"项目","cardinality":"MANY","regionId":"repeat","supportingElementIds":["row-group"]}
                  ]
                }
                """;
    }

    private static String relationshipRegionHierarchyJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-hierarchy/2.0",
                  "rootEntityId":"document",
                  "entities":[
                    {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["root"],"supportingElementIds":["title"]},
                    {"entityId":"child","schemaKey":"child","displayName":"子项","regionIds":["orphan"],"supportingElementIds":["orphan-label"]}
                  ],
                  "relationships":[
                    {"relationshipId":"document-child","parentEntityId":"document","childEntityId":"child","fieldKey":"child","displayName":"子项","cardinality":"ONE","regionId":"orphan","supportingElementIds":["owner-group"]}
                  ]
                }
                """;
    }

    private static String reusedRelationshipGroupHierarchyJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-hierarchy/2.0",
                  "rootEntityId":"document",
                  "entities":[
                    {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["root"],"supportingElementIds":["title"]},
                    {"entityId":"first","schemaKey":"first","displayName":"第一项","regionIds":["owner"],"supportingElementIds":["owner-group"]},
                    {"entityId":"second","schemaKey":"second","displayName":"第二项","regionIds":["owner"],"supportingElementIds":["owner-group"]}
                  ],
                  "relationships":[
                    {"relationshipId":"document-first","parentEntityId":"document","childEntityId":"first","fieldKey":"first","displayName":"第一项","cardinality":"ONE","regionId":"owner","supportingElementIds":["owner-group"]},
                    {"relationshipId":"document-second","parentEntityId":"document","childEntityId":"second","fieldKey":"second","displayName":"第二项","cardinality":"ONE","regionId":"owner","supportingElementIds":["owner-group"]}
                  ]
                }
                """;
    }

    private static String flatHierarchyJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-hierarchy/2.0",
                  "rootEntityId":"document",
                  "entities":[
                    {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["root"],"supportingElementIds":["title"]}
                  ],
                  "relationships":[]
                }
                """;
    }

    private static String bindingsJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-bindings/2.0",
                  "bindings":[
                    {"elementId":"title","entityId":"document"},
                    {"elementId":"item-label","entityId":"item"}
                  ]
                }
                """;
    }

    private static String hierarchyWithoutGroupEdgeJson() {
        return """
                {
                  "contractVersion":"renderweave-visual-hierarchy/2.0",
                  "rootEntityId":"document",
                  "entities":[
                    {"entityId":"document","schemaKey":"document","displayName":"文档","regionIds":["root"],"supportingElementIds":["title","row-group"]}
                  ],
                  "relationships":[]
                }
                """;
    }
}
