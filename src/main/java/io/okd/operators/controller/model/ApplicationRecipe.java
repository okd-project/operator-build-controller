package io.okd.operators.controller.model;

import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class ApplicationRecipe {

    String name;

    List<ComponentRecipe> components;

    Set<String> versions;

    boolean dateBasedVersioning;
}
