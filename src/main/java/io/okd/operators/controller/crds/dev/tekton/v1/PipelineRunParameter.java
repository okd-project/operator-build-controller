package io.okd.operators.controller.crds.dev.tekton.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PipelineRunParameter implements KubernetesResource {

    @JsonProperty("name")
    private String name;
    @JsonProperty("value")
    private String value;
}
