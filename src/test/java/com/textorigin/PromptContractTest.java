package com.textorigin;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El prompt del modelo es el contrato del que depende la normalización de
 * {@code SegmentResultDto}: si se edita, debe seguir declarando todos los campos.
 */
class PromptContractTest {

    @Test
    void elPromptDeclaraTodosLosCamposDelContrato() throws IOException {
        String prompt;
        try (InputStream input = new ClassPathResource("prompts/deepseek-analysis.txt").getInputStream()) {
            prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(prompt)
                .contains("{text}")
                .contains("\"score\"")
                .contains("\"perplexity_indicator\"")
                .contains("\"burstiness_indicator\"")
                .contains("\"indicators\"")
                .contains("\"suspicious_fragments\"")
                .contains("\"human_evidence\"")
                .contains("\"explanation\"")
                .contains("\"false_positive_warning\"");
    }
}
