package com.hxh.apboa.pk.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hxh.apboa.common.dto.PkDeliveryDTO;
import com.hxh.apboa.common.dto.PkPatentPointsDTO;
import com.hxh.apboa.common.entity.PkArtifact;
import com.hxh.apboa.common.entity.PkMatter;
import com.hxh.apboa.common.entity.PkMatterVersion;
import com.hxh.apboa.common.vo.ChatSessionVO;

import java.util.List;

public interface PkMatterService extends IService<PkMatter> {

    boolean createMatter(PkMatter entity);

    /**
     * 按案件类型解析默认智能体 ID（未引导时返回 null）
     */
    Long resolveDefaultAgentId(String matterType);

    Long resolveDefaultAgentId(String matterType, String scene);

    boolean updateMatter(PkMatter entity);

    boolean deleteMatters(List<Long> ids);

    List<PkMatterVersion> listVersions(Long matterId);

    PkMatterVersion createVersion(PkMatterVersion entity);

    List<PkArtifact> listArtifacts(Long matterId);

    PkArtifact createArtifact(PkArtifact entity);

    /**
     * 提交专利点资产清单，案件进入 awaiting_confirm。
     */
    PkMatter submitPatentPoints(Long matterId, PkPatentPointsDTO dto);

    /**
     * 人工确认拟申请点，案件进入 in_progress。
     */
    PkMatter confirmPatentPoints(Long matterId, PkPatentPointsDTO dto);

    /**
     * 摘要确认与交付格式（UIP pk_delivery_format）。
     */
    PkMatter submitDelivery(Long matterId, PkDeliveryDTO dto);

    /**
     * 把对话会话绑到案件，写入 meta_json.chatSessions / lastSessionId。
     */
    PkMatter bindChatSession(Long matterId, Long sessionId);

    /**
     * 从对话消息回写专利点、发明人、交底正文产物。
     */
    PkMatter importChatSession(Long matterId, Long sessionId);

    /**
     * 本案件智能体下最近的对话，供手工同步。
     */
    List<ChatSessionVO> listCandidateSessions(Long matterId);

    /**
     * 机器渲染 mermaid 并导出 Word，登记为案件产物。
     */
    PkArtifact exportDocx(Long matterId, com.hxh.apboa.common.dto.PkExportDocxDTO dto);

    /**
     * 创建分步导出会话（按图示并发渲染）。
     */
    java.util.Map<String, Object> createExportSession(Long matterId, com.hxh.apboa.common.dto.PkExportSessionDTO dto);

    /**
     * 渲染会话中的单张图示。
     */
    java.util.Map<String, Object> renderExportFigure(Long matterId, String sessionId, int index,
            com.hxh.apboa.common.dto.PkExportSessionDTO dto);

    /**
     * 完成分步导出并登记 Word 产物。
     */
    PkArtifact finalizeExportSession(Long matterId, String sessionId, com.hxh.apboa.common.dto.PkExportSessionDTO dto);

    /**
     * 下载已登记的产物文件（docx 等落盘文件）。
     */
    com.hxh.apboa.common.vo.PkArtifactDownloadVO downloadArtifact(Long matterId, Long artifactId);
}
