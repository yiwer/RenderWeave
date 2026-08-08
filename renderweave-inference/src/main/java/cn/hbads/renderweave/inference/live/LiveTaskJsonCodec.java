package cn.hbads.renderweave.inference.live;

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

    String write(
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
            return JSON.writeValueAsString(new Task(
                    "renderweave-live-task/1.0", run.mode().name(), stage.name(), catalog,
                    jsonProfile, List.copyOf(repairProblemCodes)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Live inference task could not be encoded", exception);
        }
    }

    private record Task(
            String taskVersion,
            String mode,
            String stage,
            List<Artifact> artifactCatalog,
            JsonStructuralProfile jsonStructuralProfile,
            List<String> repairProblemCodes
    ) { }

    private record Artifact(
            String artifactId,
            String kind,
            int ordinal,
            Integer width,
            Integer height
    ) { }
}
