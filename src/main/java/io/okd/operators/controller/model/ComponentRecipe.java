package io.okd.operators.controller.model;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.okd.operators.controller.crds.dev.tekton.v1.PipelineRef;
import lombok.Data;

import java.util.Set;

@Data
public class ComponentRecipe {

    String name;

    String imageName;

    String branchFormat;

    Set<String> dependencies;

    ComponentType type;

    String gitUrl;

    PipelineRef pipelineRef;

    PersistentVolumeClaimSpec persistentVolumeClaim;
}
