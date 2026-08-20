package com.flashpilot.controlplane.config;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.flashpilot.controlplane.config.mapper.ConfigAuditMapper;

/**
 * 变更审计。控制面的每一次改参数都要落这张表，<b>包括被护栏驳回的</b>——
 * 驳回记录本身就是护栏在起作用的证据，面试时是加分材料。
 *
 * <p>SQL 已迁到 {@link ConfigAuditMapper}（{@code resources/mapper/ConfigAuditMapper.xml}），
 * 这一层只留业务语义：截断超长文本、把「查不到」翻成 {@link Optional}。
 * 保留这个类而不是让调用方直接注入 Mapper，是为了让迁移的影响面停在这里 ——
 * 上层七八个调用点一行都不用改。
 */
@Repository
public class ConfigAuditRepository {

    private final ConfigAuditMapper mapper;

    public ConfigAuditRepository(ConfigAuditMapper mapper) {
        this.mapper = mapper;
    }

    public record Entry(
            long id, long version, String param, String oldValue, String newValue,
            String source, String reason, boolean accepted, String guardNote, String createdAt
    ) {
    }

    public void record(long version, String param, String oldValue, String newValue,
                       String source, String reason, boolean accepted, String guardNote) {
        // 截断放在这里而不是 SQL 里：列宽是存储细节，而「reason 最多留 500 字」是业务约定。
        // 交给数据库截断的话，严格模式下是报错、非严格模式下是静默丢字符 —— 两个都不是想要的。
        mapper.insert(version, param, oldValue, newValue, source,
                truncate(reason, 500), accepted, truncate(guardNote, 250));
    }

    public List<Entry> recent(int limit) {
        return mapper.recent(limit);
    }

    /** 找最后一次「真正生效」的变更，用于一键回滚。 */
    public Optional<Entry> lastAccepted() {
        List<Entry> rows = mapper.lastAccepted();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 喂给 Agent 的「最近变更及其后果」，让它能看到自己上一步做了什么，避免来回震荡。 */
    public List<Entry> recentAccepted(int limit) {
        return mapper.recentAccepted(limit);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
