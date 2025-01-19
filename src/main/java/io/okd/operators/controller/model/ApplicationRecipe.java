package io.okd.operators.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@AllArgsConstructor
@RegisterForReflection(registerFullHierarchy = true)
public class ApplicationRecipe {

    @JsonProperty
    String name;

    @JsonProperty
    List<ComponentRecipe> components;

    @JsonProperty
    Set<String> versions;

    @JsonProperty
    boolean dateBasedVersioning;
}
