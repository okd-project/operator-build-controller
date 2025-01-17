package io.okd.operators.controller.crds.dev.tekton.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
public class TimeoutsSpec implements KubernetesResource {

    @JsonProperty("pipeline")
    String pipeline;

    @JsonProperty("tasks")
    String tasks;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @JsonProperty("finally")
    String finallyTimeout;

    public String getFinally() {
        return finallyTimeout;
    }

    public void setFinally(String finallyTimeout) {
        this.finallyTimeout = finallyTimeout;
    }
}
