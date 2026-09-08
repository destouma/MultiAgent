package com.multiagent.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads persona JSON files from disk, mirroring desktop/electron/services/personaRegistry.ts.
 * personas/*.json at the repo root stay the single source of truth for both clients; unlike
 * the Electron app (which distinguishes a packaged extraResources path from two dev paths),
 * this checks a small list of candidate directories uniformly since a Maven jar's working
 * directory and jpackage layout differ from Electron's.
 *
 * <p>One writable directory is layered on top: {@code %APPDATA%/MultiAgentJava/personas/}
 * (checked last, so it wins on id). The Settings persona editor writes user-defined personas
 * there; the bundled ones stay read-only.
 */
public class PersonaRegistry {
    private static final List<String> PREFERRED_ORDER = List.of("general", "researcher", "coder", "critic");
    /** A persona id doubles as its {@code <id>.json} filename, so keep it to a safe slug. */
    public static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
    private final Path userDir;
    private volatile List<Persona> personas = List.of();

    public PersonaRegistry() {
        this(defaultUserDir());
    }

    /** @param userDir the writable override directory (tests point this at a {@code @TempDir}). */
    public PersonaRegistry(Path userDir) {
        this.userDir = userDir;
    }

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

    /** The writable override directory - shown as a hint in the editor, and created lazily on first save. */
    public Path userPersonaDir() {
        return userDir;
    }

    /** True when {@code <userDir>/<id>.json} exists - i.e. the user created or overrode this persona and may edit/remove it. */
    public boolean isUserPersona(String id) {
        return id != null && Files.isRegularFile(userDir.resolve(id + ".json"));
    }

    /** Writes (or overwrites) {@code <userDir>/<id>.json} and reloads. */
    public synchronized Persona saveUserPersona(Persona persona) {
        if (persona == null || !persona.isValid()) {
            throw new IllegalArgumentException("A persona needs an id, a name and a system prompt.");
        }
        if (!VALID_ID.matcher(persona.getId()).matches()) {
            throw new IllegalArgumentException("Persona id must be lowercase letters, digits and dashes (e.g. \"security-auditor\").");
        }
        try {
            Files.createDirectories(userDir);
            writer.writeValue(userDir.resolve(persona.getId() + ".json").toFile(), persona);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save persona \"" + persona.getId() + "\": " + e.getMessage(), e);
        }
        load();
        return persona;
    }

    /** Deletes {@code <userDir>/<id>.json} if present and reloads; returns whether a file was removed. */
    public synchronized boolean deleteUserPersona(String id) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            return false;
        }
        boolean removed;
        try {
            removed = Files.deleteIfExists(userDir.resolve(id + ".json"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not delete persona \"" + id + "\": " + e.getMessage(), e);
        }
        load();
        return removed;
    }

    private static Path defaultUserDir() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("MultiAgentJava").resolve("personas");
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
        // Last, so a user-defined persona wins over a bundled one with the same id.
        dirs.add(userDir);
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
