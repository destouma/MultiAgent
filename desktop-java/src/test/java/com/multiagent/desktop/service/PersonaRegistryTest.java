package com.multiagent.desktop.service;

import com.multiagent.desktop.model.Persona;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the real repo-root personas/*.json (resolved via "../personas" relative to
 * the module's working directory, same as PersonaRegistry's dev-path candidate), so this
 * doubles as a check that the checked-in persona files still parse and load in the
 * preferred order, mirroring desktop/electron/services/personaRegistry.test-equivalent
 * coverage on the TS side.
 */
class PersonaRegistryTest {

    @Test
    void loadsRepoPersonasInPreferredOrderWithGeneralFirst() {
        List<Persona> personas = new PersonaRegistry().load();

        assertFalse(personas.isEmpty());
        assertEquals("general", personas.get(0).getId());

        List<String> ids = personas.stream().map(Persona::getId).toList();
        int generalIndex = ids.indexOf("general");
        int researcherIndex = ids.indexOf("researcher");
        int coderIndex = ids.indexOf("coder");
        int criticIndex = ids.indexOf("critic");
        if (researcherIndex >= 0) {
            assertTrue(generalIndex < researcherIndex);
        }
        if (coderIndex >= 0) {
            assertTrue(generalIndex < coderIndex);
        }
        if (criticIndex >= 0) {
            assertTrue(generalIndex < criticIndex);
        }
    }

    @Test
    void everyLoadedPersonaHasARequiredIdNameAndSystemPrompt() {
        List<Persona> personas = new PersonaRegistry().load();
        for (Persona persona : personas) {
            assertTrue(persona.isValid(), "Persona " + persona.getId() + " should be valid");
        }
    }

    @Test
    void getReturnsEmptyForAnUnknownPersonaId() {
        PersonaRegistry registry = new PersonaRegistry();
        registry.load();
        Optional<Persona> result = registry.get("does-not-exist");
        assertTrue(result.isEmpty());
    }

    @Test
    void listLazilyLoadsWithoutRequiringAnExplicitLoadCall() {
        PersonaRegistry registry = new PersonaRegistry();
        assertFalse(registry.list().isEmpty());
    }
}
