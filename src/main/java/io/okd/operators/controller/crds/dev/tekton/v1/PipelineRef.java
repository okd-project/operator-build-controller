package io.okd.operators.controller.crds.dev.tekton.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.Data;

@Data
public class PipelineRef implements KubernetesResource {

    @JsonProperty("name")
    private String name;
}
