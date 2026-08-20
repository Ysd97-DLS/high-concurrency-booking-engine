package com.flashpilot.controlplane.config.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.flashpilot.controlplane.config.ConfigAuditRepository.Entry;

/**
 * 变更审计表的数据访问。SQL 在 {@code resources/mapper/ConfigAuditMapper.xml}。
 *
 * <p>这一层只负责「怎么读写这张表」，不含业务判断 ——
 * 截断超长 reason、把「没有记录」翻成 {@code Optional.empty()} 这类事情留在
 * {@link com.flashpilot.controlplane.config.ConfigAuditRepository} 里。
 * 分开的理由很实际：Mapper 方法要和 XML 里的 id 一一对应，
 * 掺进业务逻辑之后就没法一眼看出「这个方法对应哪条 SQL」了。
 */
public interface ConfigAuditMapper {

    /** 落一条审计。<b>包括被护栏驳回的</b> —— 驳回记录本身就是护栏在起作用的证据。 */
    void insert(@Param("version") long version,
                @Param("param") String param,
                @Param("oldValue") String oldValue,
                @Param("newValue") String newValue,
                @Param("source") String source,
                @Param("reason") String reason,
                @Param("accepted") boolean accepted,
                @Param("guardNote") String guardNote);

    /** 最近 N 条，含被驳回的。 */
    List<Entry> recent(@Param("limit") int limit);

    /** 最后一次「真正生效」的变更，用于一键回滚。至多一条。 */
    List<Entry> lastAccepted();

    /** 最近 N 条已生效的，喂给 Agent 让它看到自己上一步做了什么，避免来回震荡。 */
    List<Entry> recentAccepted(@Param("limit") int limit);
}
