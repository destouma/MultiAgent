package com.multiagent.intellij.core.service;

import com.multiagent.intellij.core.model.Persona;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void savesAUserPersonaToTheOverrideDirAndLoadsItBack(@TempDir Path userDir) {
        PersonaRegistry registry = new PersonaRegistry(userDir);
        Persona custom = new Persona("security-auditor", "Security Auditor",
                "Threat-models the change", "You are a security auditor.", "#DC2626");

        registry.saveUserPersona(custom);

        assertTrue(Files.isRegularFile(userDir.resolve("security-auditor.json")));
        assertTrue(registry.isUserPersona("security-auditor"));
        assertFalse(registry.isUserPersona("coder"), "a bundled persona is not user-editable");
        Optional<Persona> loaded = registry.get("security-auditor");
        assertTrue(loaded.isPresent());
        assertEquals("Security Auditor", loaded.get().getName());
        assertEquals("#DC2626", loaded.get().getColor());
    }

    @Test
    void aUserPersonaWinsOverABundledOneWithTheSameId(@TempDir Path userDir) {
        PersonaRegistry registry = new PersonaRegistry(userDir);
        registry.saveUserPersona(new Persona("coder", "My Coder",
                "overridden", "You are my custom coder.", "#111111"));

        assertEquals("My Coder", registry.get("coder").orElseThrow().getName());
        assertTrue(registry.isUserPersona("coder"));
    }

    @Test
    void deleteUserPersonaRemovesTheFileAndFallsBackToTheBundledOne(@TempDir Path userDir) {
        PersonaRegistry registry = new PersonaRegistry(userDir);
        registry.saveUserPersona(new Persona("coder", "My Coder",
                "overridden", "You are my custom coder.", "#111111"));
        assertEquals("My Coder", registry.get("coder").orElseThrow().getName());

        assertTrue(registry.deleteUserPersona("coder"));
        assertFalse(registry.isUserPersona("coder"));
        assertEquals("Coder", registry.get("coder").orElseThrow().getName(), "bundled coder is back");
        assertFalse(registry.deleteUserPersona("coder"), "second delete is a no-op");
    }

    @Test
    void rejectsAnUnsafePersonaId(@TempDir Path userDir) {
        PersonaRegistry registry = new PersonaRegistry(userDir);
        assertThrows(IllegalArgumentException.class, () -> registry.saveUserPersona(
                new Persona("../evil", "Evil", "x", "prompt", "#000000")));
        assertThrows(IllegalArgumentException.class, () -> registry.saveUserPersona(
                new Persona("Bad Id", "Bad", "x", "prompt", "#000000")));
    }

    @Test
    void savedUserPersonaJsonOmitsNullOptionalFields(@TempDir Path userDir) throws Exception {
        PersonaRegistry registry = new PersonaRegistry(userDir);
        registry.saveUserPersona(new Persona("minimal", "Minimal", null, "Just a prompt.", null));

        String json = Files.readString(userDir.resolve("minimal.json"));
        assertFalse(json.contains("defaultModel"));
        assertFalse(json.contains("\"color\""));
        assertFalse(json.contains("\"description\""));
    }
}
