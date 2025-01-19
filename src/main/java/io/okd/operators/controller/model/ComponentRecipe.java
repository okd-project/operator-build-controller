package io.okd.operators.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.okd.operators.controller.crds.dev.tekton.v1.PipelineRef;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
public class ComponentRecipe {

    @JsonProperty
    String name;

    @JsonProperty
    String imageName;

    @JsonProperty
    String branchFormat;

    @JsonProperty
    Set<String> dependencies;

    @JsonProperty
    ComponentType type;

    @JsonProperty
    String gitUrl;

    @JsonProperty
    PipelineRef pipelineRef;

    @JsonProperty
    PersistentVolumeClaimSpec persistentVolumeClaim;
}
