package io.okd.operators.controller.crds.dev.tekton.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import lombok.Data;

@Data
public class TaskRunTemplate implements KubernetesResource {

    @JsonProperty("podTemplate")
    private PodTemplateSpec podTemplate;

    @JsonProperty("serviceAccountName")
    private String serviceAccountName;
}
