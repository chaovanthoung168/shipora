package com.shipora.project.application.dto;


public record ProjectInput(
        String name,
        String slug,
        String description
) {
}
