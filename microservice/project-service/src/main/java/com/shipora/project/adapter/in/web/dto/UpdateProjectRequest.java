package com.shipora.project.adapter.in.web.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(

        @NotBlank(message = "Project name is required.")
        @Size(max = 150)
        String name,

        @NotBlank(message = "Project slug is required.")
        @Size(max = 100)
        String slug,

        @Size(max = 5000)
        String description

) {
}