package com.multiagent.desktop.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * Schema setup, mirroring conversationStore.ts's migrate(). Creates the full schema
 * (including `folders`/`file_checkpoints`, unused until Phase 2) up front so later
 * phases need no migration surprises, and guards every ADD COLUMN with a
 * PRAGMA table_info check so re-running this against an existing db is always safe.
 */
final class Migrations {
    private Migrations() {
    }

    static void run(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                      id TEXT PRIMARY KEY,
                      title TEXT NOT NULL,
                      createdAt INTEGER NOT NULL,
                      updatedAt INTEGER NOT NULL,
                      workspacePath TEXT,
                      kind TEXT DEFAULT 'chat',
                      model TEXT,
                      serverId TEXT,
                      personaId TEXT
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                      id TEXT PRIMARY KEY,
                      conversationId TEXT NOT NULL,
                      role TEXT NOT NULL,
                      content TEXT NOT NULL,
                      personaId TEXT,
                      createdAt INTEGER NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_conversation "
                    + "ON messages(conversationId, createdAt)");

            addColumnIfMissing(st, "conversations", "workspacePath", "TEXT");
            addColumnIfMissing(st, "conversations", "kind", "TEXT DEFAULT 'chat'");
            addColumnIfMissing(st, "conversations", "model", "TEXT");
            addColumnIfMissing(st, "conversations", "serverId", "TEXT");
            addColumnIfMissing(st, "conversations", "personaId", "TEXT");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS folders (
                      path TEXT PRIMARY KEY,
                      addedAt INTEGER NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS file_checkpoints (
                      id TEXT PRIMARY KEY,
                      conversationId TEXT NOT NULL,
                      relativePath TEXT NOT NULL,
                      previousContent TEXT,
                      previousExisted INTEGER NOT NULL,
                      createdAt INTEGER NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_checkpoints_conversation "
                    + "ON file_checkpoints(conversationId)");
        }
    }

    private static void addColumnIfMissing(Statement st, String table, String column, String definition)
            throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        if (!columns.contains(column)) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
