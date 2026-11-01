/*      This file is part of the Velocity Playtime project.
        Copyright (C) 2024-2025 _1ms

        This program is free software: you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation, either version 3 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful,
        but WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
        GNU General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program.  If not, see <https://www.gnu.org/licenses/>. */

package _1ms.playtime.Handlers;

import _1ms.playtime.Main;

import java.sql.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MySQLHandler {
    private final ConfigHandler configHandler;
    private final Main main;
    public Connection conn;

    public MySQLHandler(ConfigHandler configHandler, Main main) {
        this.configHandler = configHandler;
        this.main = main;
    }

    // All operations on the shared connection, including reload/reconnect, are serialized.
    public synchronized boolean openConnection() {
        closeConnection();
        try {
            // Do not depend on the ordering of JDBC service files in the shaded JAR.
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException missingDriver) {
            main.getLogger().error("MySQL JDBC driver is unavailable", missingDriver);
            return false;
        }
        final String url = String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000",
                configHandler.getADDRESS(), configHandler.getPORT(), configHandler.getDB_NAME());
        try {
            conn = DriverManager.getConnection(url, configHandler.getUSERNAME(), configHandler.getPASSWORD());
            ensureSchema();
            return true;
        } catch (SQLException e) {
            closeConnection();
            main.getLogger().error("Error while connecting to the database: {}", e.getMessage());
            return false;
        }
    }

    private void ensureSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS playtimes (name VARCHAR(36) PRIMARY KEY, time BIGINT NOT NULL, last_name VARCHAR(16))");
            if(getColumnSize("name") < 36)
                s.execute("ALTER TABLE playtimes MODIFY COLUMN name VARCHAR(36) NOT NULL");
            if(getColumnSize("last_name") == 0)
                s.execute("ALTER TABLE playtimes ADD COLUMN last_name VARCHAR(16)");
        }
    }

    private int getColumnSize(String columnName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, "playtimes", columnName)) {
            return rs.next() ? rs.getInt("COLUMN_SIZE") : 0;
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }

    private synchronized <T> T execute(String operation, SqlOperation<T> action) {
        SQLException failure = null;
        for(int attempt = 0; attempt < 2; attempt++) {
            try {
                if((conn == null || conn.isClosed()) && !openConnection())
                    throw new SQLNonTransientConnectionException("Could not open the database connection", "08001");
                return action.run();
            } catch (SQLException e) {
                failure = e;
                boolean connectionFailure = e instanceof SQLNonTransientConnectionException
                        || e instanceof SQLTransientConnectionException || e instanceof SQLRecoverableException
                        || (e.getSQLState() != null && e.getSQLState().startsWith("08"));
                if(!connectionFailure)
                    break;
                closeConnection();
            }
        }
        throw new StorageException("Database operation failed: " + operation, failure);
    }

    public void saveData(String name, String lastName, long time) {
        if(time < 0)
            throw new IllegalArgumentException("Playtime cannot be negative");
        execute("save playtime", () -> {
            try (PreparedStatement s = conn.prepareStatement("INSERT INTO playtimes (name, time, last_name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE time = ?, last_name = ?")) {
                s.setString(1, name);
                s.setLong(2, time);
                s.setString(3, lastName);
                s.setLong(4, time);
                s.setString(5, lastName);
                s.executeUpdate();
            }
            return null;
        });
    }

    public void saveName(String name, String lastName) {
        if(lastName == null || lastName.isBlank())
            return;
        execute("save player name", () -> {
            try (PreparedStatement s = conn.prepareStatement("UPDATE playtimes SET last_name = ? WHERE name = ?")) {
                s.setString(1, lastName);
                s.setString(2, name);
                s.executeUpdate();
            }
            return null;
        });
    }

    public long readData(String name) {
        return execute("read playtime", () -> {
            try (PreparedStatement s = conn.prepareStatement("SELECT time FROM playtimes WHERE name = ?")) {
                s.setString(1, name);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? checkedTime(rs.getLong("time")) : -1L;
                }
            }
        });
    }

    private long checkedTime(long time) throws SQLException {
        if(time < 0)
            throw new SQLException("Stored playtime is negative; refusing to load or overwrite it");
        return time;
    }

    public Map<String, Long> readAllTimes() {
        return execute("read all playtimes", () -> {
            Map<String, Long> times = new HashMap<>();
            try (PreparedStatement s = conn.prepareStatement("SELECT name, time FROM playtimes"); ResultSet rs = s.executeQuery()) {
                while(rs.next())
                    times.put(rs.getString("name"), checkedTime(rs.getLong("time")));
            }
            return times;
        });
    }

    public Map<String, String> readAllNames() {
        return execute("read player names", () -> {
            Map<String, String> names = new HashMap<>();
            try(PreparedStatement s = conn.prepareStatement("SELECT name, last_name FROM playtimes"); ResultSet rs = s.executeQuery()) {
                while(rs.next()) {
                    String key = rs.getString("name");
                    String name = rs.getString("last_name");
                    names.put(key, name == null || name.isBlank() ? key : name);
                }
            }
            return names;
        });
    }

    public String readName(String name) {
        return execute("read player name", () -> {
            try (PreparedStatement s = conn.prepareStatement("SELECT last_name FROM playtimes WHERE name = ?")) {
                s.setString(1, name);
                try (ResultSet rs = s.executeQuery()) {
                    String lastName = rs.next() ? rs.getString("last_name") : null;
                    return lastName == null || lastName.isBlank() ? name : lastName;
                }
            }
        });
    }

    public String findKeyByName(String playerName) {
        return execute("find player key", () -> {
            try (PreparedStatement s = conn.prepareStatement("SELECT name FROM playtimes WHERE LOWER(last_name) = LOWER(?)")) {
                s.setString(1, playerName);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? rs.getString("name") : null;
                }
            }
        });
    }

    public Iterator<String> getIterator() {
        return readAllTimes().keySet().iterator();
    }

    public void deleteAll() {
        execute("delete all playtimes", () -> {
            try (PreparedStatement s = conn.prepareStatement("DELETE FROM playtimes")) {
                s.executeUpdate();
            }
            return null;
        });
    }

    public void deleteData(String name) {
        execute("delete playtime", () -> {
            try (PreparedStatement s = conn.prepareStatement("DELETE FROM playtimes WHERE name = ?")) {
                s.setString(1, name);
                s.executeUpdate();
            }
            return null;
        });
    }

    public synchronized void closeConnection() {
        Connection previous = conn;
        conn = null;
        if(previous != null) {
            try {
                previous.close();
            } catch (SQLException e) {
                main.getLogger().warn("Error closing database connection", e);
            }
        }
    }
}
