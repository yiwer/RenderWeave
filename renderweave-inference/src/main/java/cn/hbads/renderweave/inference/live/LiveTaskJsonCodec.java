package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfile;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceStage;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;

final class LiveTaskJsonCodec {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    String writeV1(
            InferenceRunSnapshot run,
            InferenceStage stage,
            JsonStructuralProfile jsonProfile,
            List<String> repairProblemCodes
    ) {
        try {
            var catalog = run.inputs().stream()
                    .sorted(Comparator.comparing((cn.hbads.renderweave.inference.run.InferenceRunInput input) ->
                            input.kind().name()).thenComparingInt(input -> input.ordinal()))
                    .map(input -> new Artifact(
                            input.artifact().artifactId(), input.kind().name(), input.ordinal(),
                            input.artifact().width(), input.artifact().height()
                    ))
                    .toList();
            return JSON.writeValueAsString(new TaskV1(
                    "renderweave-live-task/1.0", run.mode().name(), stage.name(), catalog,
                    jsonProfile, List.copyOf(repairProblemCodes)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Live inference task could not be encoded", exception);
        }
    }

    String writeV2(
            InferenceRunSnapshot run,
            InferenceStage stage,
            JsonStructuralProfile jsonProfile,
            CandidateBundle groundedCandidate,
            List<String> repairProblemCodes
    ) {
        try {
            return JSON.writeValueAsString(new TaskV2(
                    "renderweave-live-task/2.0",
                    run.mode().name(),
                    stage.name(),
                    artifacts(run),
                    jsonProfile,
                    groundedCandidate,
                    List.copyOf(repairProblemCodes)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Live inference task could not be encoded", exception);
        }
    }

    String writeV3(
            InferenceRunSnapshot run,
            InferenceStage stage,
            VisualElementInventory elementInventory,
            VisualHierarchyPlan hierarchyPlan,
            VisualElementBindingPlan bindingPlan,
            List<String> repairProblemCodes,
            List<String> retryProblemCodes
    ) {
        try {
            return JSON.writeValueAsString(new TaskV3(
                    "renderweave-live-task/3.0",
                    run.mode().name(),
                    stage.name(),
                    artifacts(run),
                    elementInventory,
                    hierarchyPlan,
                    bindingPlan,
                    List.copyOf(repairProblemCodes),
                    List.copyOf(retryProblemCodes)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Live inference task could not be encoded", exception);
        }
    }

    String writeV4(
            InferenceRunSnapshot run,
            InferenceStage stage,
            VisualViewPlan viewPlan,
            String hintPackVersion,
            VisualElementInventory elementInventory,
            VisualGroundingPlan groundingPlan,
            VisualHierarchyPlan hierarchyPlan,
            VisualEntityRegionPlan entityRegionPlan,
            VisualElementBindingPlan bindingPlan,
            List<String> repairProblemCodes,
            List<String> retryProblemCodes
    ) {
        try {
            return JSON.writeValueAsString(new TaskV4(
                    "renderweave-live-task/4.0",
                    run.mode().name(),
                    stage.name(),
                    hintPackVersion,
                    viewPlan.planVersion(),
                    viewPlan.descriptors(),
                    elementInventory,
                    groundingPlan,
                    hierarchyPlan,
                    entityRegionPlan,
                    bindingPlan,
                    List.copyOf(repairProblemCodes),
                    List.copyOf(retryProblemCodes)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Grounded visual task could not be encoded", exception);
        }
    }

    private static List<Artifact> artifacts(InferenceRunSnapshot run) {
        return run.inputs().stream()
                .sorted(Comparator.comparing((cn.hbads.renderweave.inference.run.InferenceRunInput input) ->
                        input.kind().name()).thenComparingInt(input -> input.ordinal()))
                .map(input -> new Artifact(
                        input.artifact().artifactId(), input.kind().name(), input.ordinal(),
                        input.artifact().width(), input.artifact().height()
                ))
                .toList();
    }

    private record TaskV1(
            String taskVersion,
            String mode,
            String stage,
            List<Artifact> artifactCatalog,
            JsonStructuralProfile jsonStructuralProfile,
            List<String> repairProblemCodes
    ) { }

    private record TaskV2(
            String taskVersion,
            String mode,
            String stage,
            List<Artifact> artifactCatalog,
            JsonStructuralProfile jsonStructuralProfile,
            CandidateBundle groundedCandidate,
            List<String> repairProblemCodes
    ) { }

    private record TaskV3(
            String taskVersion,
            String mode,
            String stage,
            List<Artifact> artifactCatalog,
            VisualElementInventory elementInventory,
            VisualHierarchyPlan hierarchyPlan,
            VisualElementBindingPlan bindingPlan,
            List<String> repairProblemCodes,
            List<String> retryProblemCodes
    ) { }

    private record TaskV4(
            String taskVersion,
            String mode,
            String stage,
            String hintPackVersion,
            String viewPlanVersion,
            List<VisualViewDescriptor> viewCatalog,
            VisualElementInventory elementInventory,
            VisualGroundingPlan groundingPlan,
            VisualHierarchyPlan hierarchyPlan,
            VisualEntityRegionPlan entityRegionPlan,
            VisualElementBindingPlan bindingPlan,
            List<String> repairProblemCodes,
            List<String> retryProblemCodes
    ) { }

    private record Artifact(
            String artifactId,
            String kind,
            int ordinal,
            Integer width,
            Integer height
    ) { }
}
