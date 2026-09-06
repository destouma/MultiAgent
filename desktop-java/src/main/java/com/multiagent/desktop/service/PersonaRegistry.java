package com.multiagent.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.desktop.model.Persona;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads persona JSON files from disk, mirroring desktop/electron/services/personaRegistry.ts.
 * personas/*.json at the repo root stay the single source of truth for both clients; unlike
 * the Electron app (which distinguishes a packaged extraResources path from two dev paths),
 * this checks a small list of candidate directories uniformly since a Maven jar's working
 * directory and jpackage layout differ from Electron's.
 */
public class PersonaRegistry {
    private static final List<String> PREFERRED_ORDER = List.of("general", "researcher", "coder", "critic");

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile List<Persona> personas = List.of();

    public synchronized List<Persona> load() {
        Map<String, Persona> byId = new LinkedHashMap<>();
        for (Path dir : personaDirs()) {
            for (Persona persona : readPersonasFromDir(dir)) {
                byId.put(persona.getId(), persona);
            }
        }

        List<Persona> loaded = new ArrayList<>(byId.values());
        loaded.sort((a, b) -> {
            int ai = PREFERRED_ORDER.indexOf(a.getId());
            int bi = PREFERRED_ORDER.indexOf(b.getId());
            if (ai == -1 && bi == -1) return a.getName().compareTo(b.getName());
            if (ai == -1) return 1;
            if (bi == -1) return -1;
            return Integer.compare(ai, bi);
        });

        personas = loaded.isEmpty() ? List.of(fallbackPersona()) : loaded;
        return personas;
    }

    public synchronized List<Persona> list() {
        return personas.isEmpty() ? load() : personas;
    }

    public Optional<Persona> get(String id) {
        return list().stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    private static Persona fallbackPersona() {
        return new Persona(
                "general",
                "General",
                "Helpful all-purpose assistant",
                "You are a helpful, concise assistant. Answer clearly and ask clarifying questions when needed.",
                "#0F766E");
    }

    private List<Path> personaDirs() {
        List<Path> dirs = new ArrayList<>();
        Path cwd = Path.of("").toAbsolutePath();
        dirs.add(cwd.resolve("../personas").normalize());
        dirs.add(cwd.resolve("personas").normalize());
        try {
            Path codeSource = Path.of(PersonaRegistry.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path appDir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (appDir != null) {
                dirs.add(appDir.resolve("personas").normalize());
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // No code source (e.g. some test runners) - filesystem candidates above still apply.
        }
        return dirs;
    }

    private List<Persona> readPersonasFromDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(this::readPersona)
                    .filter(Objects::nonNull)
                    .filter(Persona::isValid)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Persona readPersona(Path file) {
        try {
            return mapper.readValue(file.toFile(), Persona.class);
        } catch (IOException e) {
            return null;
        }
    }
}
