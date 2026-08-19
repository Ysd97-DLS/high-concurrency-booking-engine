package com.flashpilot.controlplane.config;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 变更审计。控制面的每一次改参数都要落这张表，<b>包括被护栏驳回的</b>——
 * 驳回记录本身就是护栏在起作用的证据，面试时是加分材料。
 */
@Repository
public class ConfigAuditRepository {

    private final JdbcTemplate jdbc;

    public ConfigAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(
            long id, long version, String param, String oldValue, String newValue,
            String source, String reason, boolean accepted, String guardNote, String createdAt
    ) {
    }

    public void record(long version, String param, String oldValue, String newValue,
                       String source, String reason, boolean accepted, String guardNote) {
        jdbc.update("""
                        INSERT INTO t_config_audit
                            (version, param, old_value, new_value, source, reason, accepted, guard_note)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                version, param, oldValue, newValue, source, truncate(reason, 500),
                accepted ? 1 : 0, truncate(guardNote, 250));
    }

    public List<Entry> recent(int limit) {
        return jdbc.query("""
                SELECT id, version, param, old_value, new_value, source, reason, accepted, guard_note,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
                FROM t_config_audit
                ORDER BY id DESC
                LIMIT ?
                """, (rs, n) -> new Entry(
                rs.getLong("id"), rs.getLong("version"), rs.getString("param"),
                rs.getString("old_value"), rs.getString("new_value"), rs.getString("source"),
                rs.getString("reason"), rs.getBoolean("accepted"), rs.getString("guard_note"),
                rs.getString("created_at")), limit);
    }

    /** 找最后一次「真正生效」的变更，用于一键回滚。 */
    public Optional<Entry> lastAccepted() {
        List<Entry> rows = jdbc.query("""
                SELECT id, version, param, old_value, new_value, source, reason, accepted, guard_note,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
                FROM t_config_audit
                WHERE accepted = 1 AND source <> 'ROLLBACK' AND old_value IS NOT NULL
                ORDER BY id DESC
                LIMIT 1
                """, (rs, n) -> new Entry(
                rs.getLong("id"), rs.getLong("version"), rs.getString("param"),
                rs.getString("old_value"), rs.getString("new_value"), rs.getString("source"),
                rs.getString("reason"), rs.getBoolean("accepted"), rs.getString("guard_note"),
                rs.getString("created_at")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 喂给 Agent 的「最近变更及其后果」，让它能看到自己上一步做了什么，避免来回震荡。 */
    public List<Entry> recentAccepted(int limit) {
        return jdbc.query("""
                SELECT id, version, param, old_value, new_value, source, reason, accepted, guard_note,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
                FROM t_config_audit
                WHERE accepted = 1
                ORDER BY id DESC
                LIMIT ?
                """, (rs, n) -> new Entry(
                rs.getLong("id"), rs.getLong("version"), rs.getString("param"),
                rs.getString("old_value"), rs.getString("new_value"), rs.getString("source"),
                rs.getString("reason"), rs.getBoolean("accepted"), rs.getString("guard_note"),
                rs.getString("created_at")), limit);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
