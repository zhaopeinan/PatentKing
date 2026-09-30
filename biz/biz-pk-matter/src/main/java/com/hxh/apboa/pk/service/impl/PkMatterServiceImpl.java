package com.hxh.apboa.pk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hxh.apboa.common.dto.PkDeliveryDTO;
import com.hxh.apboa.common.dto.PkExportDocxDTO;
import com.hxh.apboa.common.dto.PkExportSessionDTO;
import com.hxh.apboa.common.dto.PkPatentPointsDTO;
import com.hxh.apboa.common.entity.ChatMessage;
import com.hxh.apboa.common.entity.PkArtifact;
import com.hxh.apboa.common.entity.PkMatter;
import com.hxh.apboa.common.entity.PkMatterVersion;
import com.hxh.apboa.common.util.JsonUtils;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.common.vo.ChatSessionVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.vo.PkArtifactDownloadVO;
import com.hxh.apboa.pk.PkChatSessionImport;
import com.hxh.apboa.pk.PkDeliveryMeta;
import com.hxh.apboa.pk.PkExportSupport;
import com.hxh.apboa.pk.PkMatterAgentCodes;
import com.hxh.apboa.pk.PkMatterChatMeta;
import com.hxh.apboa.pk.PkMatterSceneMeta;
import com.hxh.apboa.pk.PkPatentPointsMeta;
import com.hxh.apboa.pk.PkTokenlabSettingsStore;
import com.hxh.apboa.pk.mapper.PkArtifactMapper;
import com.hxh.apboa.pk.mapper.PkMatterMapper;
import com.hxh.apboa.pk.mapper.PkMatterVersionMapper;
import com.hxh.apboa.pk.service.PkMatterService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PkMatterServiceImpl extends ServiceImpl<PkMatterMapper, PkMatter> implements PkMatterService {

    private final PkMatterVersionMapper versionMapper;
    private final PkArtifactMapper artifactMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PkExportSupport exportSupport;
    private final PkTokenlabSettingsStore tokenlabStore;

    @Override
    public boolean createMatter(PkMatter entity) {
        if (!StringUtils.hasText(entity.getStatus())) {
            entity.setStatus("draft");
        }
        String rawType = entity.getMatterType();
        String scene = PkMatterSceneMeta.readScene(entity.getMetaJson());
        if (!StringUtils.hasText(scene)) {
            scene = PkMatterAgentCodes.inferSceneFromLegacyType(rawType);
        }
        String normalized = PkMatterAgentCodes.normalizeMatterType(rawType);
        entity.setMatterType(normalized);
        // oa 用一级类型表达；其余 disclosure 家族写入 scene
        if ("disclosure".equals(normalized) && StringUtils.hasText(scene) && !"write".equals(scene)) {
            entity.setMetaJson(PkMatterSceneMeta.mergeScene(entity.getMetaJson(), scene));
        } else if ("disclosure".equals(normalized) && "write".equals(scene)) {
            entity.setMetaJson(PkMatterSceneMeta.mergeScene(entity.getMetaJson(), "write"));
        } else if ("oa".equals(normalized)) {
            entity.setMetaJson(PkMatterSceneMeta.mergeScene(entity.getMetaJson(), "oa"));
        }
        if (entity.getAgentDefinitionId() == null) {
            entity.setAgentDefinitionId(resolveDefaultAgentId(normalized, scene));
        }
        return save(entity);
    }

    @Override
    public Long resolveDefaultAgentId(String matterType) {
        return resolveDefaultAgentId(matterType, null);
    }

    public Long resolveDefaultAgentId(String matterType, String scene) {
        String code = PkMatterAgentCodes.codeForMatterType(matterType, scene);
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (tenantId == null) {
            return null;
        }
        var ids = jdbcTemplate.query(
                "SELECT id FROM agent_definition WHERE agent_code = ? AND tenant_id = ? AND enabled = 1 LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                code, tenantId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    @Override
    public boolean updateMatter(PkMatter entity) {
        return updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMatters(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        artifactMapper.delete(new LambdaQueryWrapper<PkArtifact>().in(PkArtifact::getMatterId, ids));
        versionMapper.delete(new LambdaQueryWrapper<PkMatterVersion>().in(PkMatterVersion::getMatterId, ids));
        return removeByIds(ids);
    }

    @Override
    public List<PkMatterVersion> listVersions(Long matterId) {
        return versionMapper.selectList(new LambdaQueryWrapper<PkMatterVersion>()
                .eq(PkMatterVersion::getMatterId, matterId)
                .orderByDesc(PkMatterVersion::getVersionNo));
    }

    @Override
    public PkMatterVersion createVersion(PkMatterVersion entity) {
        if (entity.getVersionNo() == null) {
            List<PkMatterVersion> existing = listVersions(entity.getMatterId());
            int next = existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1;
            entity.setVersionNo(next);
        }
        versionMapper.insert(entity);
        return entity;
    }

    @Override
    public List<PkArtifact> listArtifacts(Long matterId) {
        return artifactMapper.selectList(new LambdaQueryWrapper<PkArtifact>()
                .eq(PkArtifact::getMatterId, matterId)
                .orderByDesc(PkArtifact::getCreatedAt));
    }

    @Override
    public PkArtifact createArtifact(PkArtifact entity) {
        artifactMapper.insert(entity);
        return entity;
    }

    @Override
    public PkMatter submitPatentPoints(Long matterId, PkPatentPointsDTO dto) {
        PkMatter matter = requireMatter(matterId);
        matter.setMetaJson(PkPatentPointsMeta.mergeSubmit(matter.getMetaJson(), dto == null ? new PkPatentPointsDTO() : dto));
        matter.setStatus("awaiting_confirm");
        updateById(matter);
        return getById(matterId);
    }

    @Override
    public PkMatter confirmPatentPoints(Long matterId, PkPatentPointsDTO dto) {
        PkMatter matter = requireMatter(matterId);
        PkPatentPointsDTO body = dto == null ? new PkPatentPointsDTO() : dto;
        matter.setMetaJson(PkPatentPointsMeta.mergeConfirm(matter.getMetaJson(), body));
        matter.setStatus("in_progress");
        updateById(matter);
        return getById(matterId);
    }

    @Override
    public PkMatter submitDelivery(Long matterId, PkDeliveryDTO dto) {
        PkMatter matter = requireMatter(matterId);
        matter.setMetaJson(PkDeliveryMeta.merge(matter.getMetaJson(), dto));
        updateById(matter);
        return getById(matterId);
    }

    @Override
    public PkMatter bindChatSession(Long matterId, Long sessionId) {
        PkMatter matter = requireMatter(matterId);
        SessionRow session = requireSession(sessionId);
        matter.setMetaJson(PkMatterChatMeta.bind(matter.getMetaJson(), sessionId, session.title()));
        if (!StringUtils.hasText(matter.getWorkspaceRelPath())) {
            matter.setWorkspaceRelPath("chat/" + sessionId);
        }
        updateById(matter);
        return getById(matterId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PkMatter importChatSession(Long matterId, Long sessionId) {
        PkMatter matter = bindChatSession(matterId, sessionId);
        SessionRow session = requireSession(sessionId);
        List<ChatMessage> messages = loadMessages(sessionId, session.messageTable());
        PkChatSessionImport.Result extracted = PkChatSessionImport.extract(messages);
        boolean dirty = false;
        if (extracted.isHasPoints()) {
            if (extracted.isConfirmed() || "in_progress".equals(matter.getStatus())
                    || "delivered".equals(matter.getStatus())) {
                matter.setMetaJson(PkPatentPointsMeta.mergeConfirm(matter.getMetaJson(), extracted.getPoints()));
                if (!"delivered".equals(matter.getStatus()) && !"archived".equals(matter.getStatus())) {
                    matter.setStatus("in_progress");
                }
            } else {
                matter.setMetaJson(PkPatentPointsMeta.mergeSubmit(matter.getMetaJson(), extracted.getPoints()));
                if ("draft".equals(matter.getStatus()) || "awaiting_confirm".equals(matter.getStatus())
                        || !StringUtils.hasText(matter.getStatus())) {
                    matter.setStatus("awaiting_confirm");
                }
            }
            dirty = true;
        }
        if (!StringUtils.hasText(matter.getInventorsJson()) && StringUtils.hasText(extracted.getInventor())) {
            matter.setInventorsJson(JsonUtils.toJsonStr(List.of(extracted.getInventor())));
            dirty = true;
        }
        if (dirty) {
            updateById(matter);
        }
        upsertDisclosureArtifact(matter, sessionId, extracted);
        ensureImportedVersion(matterId, sessionId);
        return getById(matterId);
    }

    @Override
    public List<ChatSessionVO> listCandidateSessions(Long matterId) {
        PkMatter matter = requireMatter(matterId);
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (matter.getAgentDefinitionId() == null || tenantId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                        SELECT id, user_id, agent_id, current_message_id, title, is_pinned, pin_time,
                               created_at, updated_at, message_table
                        FROM chat_session
                        WHERE agent_id = ? AND tenant_id = ?
                        ORDER BY updated_at DESC
                        LIMIT 30
                        """,
                this::mapSession,
                matter.getAgentDefinitionId(), tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PkArtifact exportDocx(Long matterId, PkExportDocxDTO dto) {
        PkMatter matter = requireMatter(matterId);
        PkExportDocxDTO body = dto == null ? new PkExportDocxDTO() : dto;
        String exportId = body.getExportId();
        String filename;
        byte[] bytes;
        int figureCount = 0;
        int failedCount = 0;
        JsonNode exportResult = null;
        if (StringUtils.hasText(exportId)) {
            bytes = downloadExportBytes(exportId.trim());
            filename = StringUtils.hasText(body.getTitle()) ? body.getTitle().trim() + ".docx" : "disclosure.docx";
        } else {
            String markdown = body.getMarkdown();
            if (!StringUtils.hasText(markdown)) {
                markdown = latestDisclosureMarkdown(matterId);
            }
            if (!StringUtils.hasText(markdown)) {
                throw new IllegalArgumentException("没有可导出的交底 Markdown。请先同步对话或粘贴正文。");
            }
            String title = StringUtils.hasText(body.getTitle()) ? body.getTitle().trim() : matter.getTitle();
            if (!StringUtils.hasText(title)) {
                title = "disclosure";
            }
            String mode;
            if (StringUtils.hasText(body.getDiagramMode())) {
                mode = PkTokenlabSettingsStore.normalizeDiagramMode(body.getDiagramMode());
            } else {
                mode = PkTokenlabSettingsStore.normalizeDiagramMode(
                        String.valueOf(PkDeliveryMeta.readDelivery(matter.getMetaJson()).getOrDefault("diagramMode", "png")));
            }
            if ("auto".equals(mode) && !tokenlabStore.isConfigured(TenantUtils.getCurrentTenantId())) {
                throw new IllegalStateException(
                        "已选择「自动生成」图示，但尚未配置 Tokenlab。请管理员在「系统设置 → PatentKing 生图」中填写 API Key。");
            }
            exportResult = exportSupport.callExport(markdown, title, mode, TenantUtils.getCurrentTenantId());
            if (exportResult == null || !exportResult.path("ok").asBoolean(false)) {
                String msg = exportResult == null ? "patent-tools 无响应" : exportResult.path("message").asText("导出失败");
                throw new IllegalStateException(msg);
            }
            exportId = exportResult.path("export_id").asText("");
            filename = exportResult.path("filename").asText(title + ".docx");
            figureCount = exportResult.path("figure_count").asInt(0);
            failedCount = exportResult.path("failed_count").asInt(0);
            if (!StringUtils.hasText(exportId)) {
                throw new IllegalStateException("导出成功但缺少 export_id");
            }
            bytes = downloadExportBytes(exportId);
        }
        return saveDocxArtifact(matter, exportId, filename, bytes, figureCount, failedCount, exportResult,
                StringUtils.hasText(body.getDiagramMode()) ? body.getDiagramMode() : null);
    }

    @Override
    public Map<String, Object> createExportSession(Long matterId, PkExportSessionDTO dto) {
        requireMatter(matterId);
        PkExportSessionDTO body = dto == null ? new PkExportSessionDTO() : dto;
        String markdown = body.getMarkdown();
        if (!StringUtils.hasText(markdown)) {
            markdown = latestDisclosureMarkdown(matterId);
        }
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalArgumentException("没有可导出的交底 Markdown。请先同步对话或粘贴正文。");
        }
        String mode = StringUtils.hasText(body.getDiagramMode())
                ? PkTokenlabSettingsStore.normalizeDiagramMode(body.getDiagramMode())
                : "png";
        JsonNode result = exportSupport.createExportSession(markdown, mode, TenantUtils.getCurrentTenantId());
        if (result == null || !result.path("ok").asBoolean(false)) {
            String msg = result == null ? "patent-tools 无响应" : result.path("message").asText("创建导出会话失败");
            throw new IllegalStateException(msg);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", result.path("session_id").asText(""));
        out.put("diagramMode", result.path("diagram_mode").asText(mode));
        if (result.path("figures").isArray()) {
            out.put("figures", JsonUtils.parse(result.path("figures").toString(), List.class));
        }
        out.put("message", result.path("message").asText(""));
        return out;
    }

    @Override
    public Map<String, Object> renderExportFigure(Long matterId, String sessionId, int index, PkExportSessionDTO dto) {
        requireMatter(matterId);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 为空");
        }
        PkExportSessionDTO body = dto == null ? new PkExportSessionDTO() : dto;
        boolean force = Boolean.TRUE.equals(body.getForce());
        String mode = StringUtils.hasText(body.getDiagramMode()) ? body.getDiagramMode() : "";
        JsonNode result = exportSupport.renderExportFigure(
                sessionId.trim(), index, mode, force, TenantUtils.getCurrentTenantId());
        if (result == null) {
            throw new IllegalStateException("patent-tools 无响应");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.path("ok").asBoolean(false));
        out.put("index", result.path("index").asInt(index));
        out.put("status", result.path("status").asText(""));
        out.put("engine", result.path("engine").asText(""));
        out.put("message", result.path("message").asText(""));
        out.put("preview", result.path("preview").asText(""));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PkArtifact finalizeExportSession(Long matterId, String sessionId, PkExportSessionDTO dto) {
        PkMatter matter = requireMatter(matterId);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 为空");
        }
        PkExportSessionDTO body = dto == null ? new PkExportSessionDTO() : dto;
        String title = StringUtils.hasText(body.getTitle()) ? body.getTitle().trim() : matter.getTitle();
        if (!StringUtils.hasText(title)) {
            title = "disclosure";
        }
        String mode = StringUtils.hasText(body.getDiagramMode()) ? body.getDiagramMode() : "";
        JsonNode exportResult = exportSupport.finalizeExportSession(sessionId.trim(), title, mode);
        if (exportResult == null || !exportResult.path("ok").asBoolean(false)) {
            String msg = exportResult == null ? "patent-tools 无响应" : exportResult.path("message").asText("导出失败");
            throw new IllegalStateException(msg);
        }
        String exportId = exportResult.path("export_id").asText("");
        if (!StringUtils.hasText(exportId)) {
            throw new IllegalStateException("导出成功但缺少 export_id");
        }
        String filename = exportResult.path("filename").asText(title + ".docx");
        byte[] bytes = downloadExportBytes(exportId);
        return saveDocxArtifact(
                matter,
                exportId,
                filename,
                bytes,
                exportResult.path("figure_count").asInt(0),
                exportResult.path("failed_count").asInt(0),
                exportResult,
                StringUtils.hasText(body.getDiagramMode()) ? body.getDiagramMode() : null);
    }

    private PkArtifact saveDocxArtifact(
            PkMatter matter,
            String exportId,
            String filename,
            byte[] bytes,
            int figureCount,
            int failedCount,
            JsonNode exportResult,
            String diagramModeOverride) {
        Long matterId = matter.getId();
        Path dir = Path.of(resolveStorageRoot(), "pk", String.valueOf(matterId));
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(filename.replaceAll("[\\\\/:*?\"<>|]", "_"));
            Files.write(file, bytes);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", "patent-tools");
            meta.put("exportId", exportId);
            meta.put("figureCount", figureCount);
            meta.put("failedCount", failedCount);
            meta.put("diagramMode", StringUtils.hasText(diagramModeOverride)
                    ? diagramModeOverride
                    : (exportResult != null ? exportResult.path("diagram_mode").asText("png") : "png"));
            if (exportResult != null && exportResult.path("figures").isArray()) {
                meta.put("figures", JsonUtils.parse(exportResult.path("figures").toString(), List.class));
            }
            PkArtifact artifact = new PkArtifact();
            artifact.setMatterId(matterId);
            artifact.setArtifactType("docx");
            artifact.setName(filename);
            artifact.setStorageUri(file.toAbsolutePath().toString());
            artifact.setMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            artifact.setMetaJson(JsonUtils.toJsonStr(meta));
            artifactMapper.insert(artifact);
            if (!"delivered".equals(matter.getStatus()) && !"archived".equals(matter.getStatus())) {
                matter.setStatus("in_progress");
                updateById(matter);
            }
            return artifact;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("保存 Word 失败：" + e.getMessage(), e);
        }
    }

    @Override
    public PkArtifactDownloadVO downloadArtifact(Long matterId, Long artifactId) {
        requireMatter(matterId);
        PkArtifact artifact = artifactMapper.selectById(artifactId);
        if (artifact == null || !matterId.equals(artifact.getMatterId())) {
            throw new IllegalArgumentException("产物不存在");
        }
        String uri = artifact.getStorageUri();
        if (!StringUtils.hasText(uri)) {
            throw new IllegalStateException("该产物没有可下载的文件路径");
        }
        if (uri.startsWith("chat-session:") || uri.startsWith("http://") || uri.startsWith("https://")) {
            throw new IllegalStateException("该产物是对话草稿或外链，请先导出 Word 后再下载");
        }
        Path file = Path.of(uri).toAbsolutePath().normalize();
        Path allowedRoot = Path.of(resolveStorageRoot(), "pk", String.valueOf(matterId)).toAbsolutePath().normalize();
        if (!file.startsWith(allowedRoot)) {
            throw new IllegalStateException("产物路径非法，拒绝下载");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("文件不存在或已被清理：" + file.getFileName());
        }
        try {
            String filename = StringUtils.hasText(artifact.getName())
                    ? artifact.getName()
                    : file.getFileName().toString();
            String mime = StringUtils.hasText(artifact.getMime())
                    ? artifact.getMime()
                    : "application/octet-stream";
            return new PkArtifactDownloadVO(filename, mime, Files.readAllBytes(file));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取产物失败：" + e.getMessage(), e);
        }
    }

    private String latestDisclosureMarkdown(Long matterId) {
        return listArtifacts(matterId).stream()
                .filter(a -> "disclosure_md".equals(a.getArtifactType()))
                .map(PkArtifact::getMetaJson)
                .filter(StringUtils::hasText)
                .map(json -> {
                    try {
                        JsonNode n = JsonUtils.parse(json);
                        return n == null ? null : n.path("content").asText(null);
                    } catch (RuntimeException e) {
                        return null;
                    }
                })
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private byte[] downloadExportBytes(String exportId) {
        String base = System.getenv("PK_TOOLS_BASE_URL");
        if (!StringUtils.hasText(base)) {
            base = "http://patent-tools:3070";
        }
        base = base.replaceAll("/$", "");
        try {
            URL url = URI.create(base + "/v1/export/files/" + exportId).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(120_000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IllegalStateException("下载 Word 失败 HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("下载 Word 失败：" + e.getMessage(), e);
        }
    }

    private String resolveStorageRoot() {
        String env = System.getenv("PK_MATTER_STORAGE_DIR");
        if (StringUtils.hasText(env)) {
            return env;
        }
        return "/app/.apboa/storage";
    }

    private void upsertDisclosureArtifact(PkMatter matter, Long sessionId, PkChatSessionImport.Result extracted) {
        if (!StringUtils.hasText(extracted.getDisclosureMarkdown())) {
            return;
        }
        String uriPrefix = "chat-session:" + sessionId;
        String storageUri = uriPrefix + ":msg:" + extracted.getDisclosureMessageId();
        PkArtifact existing = listArtifacts(matter.getId()).stream()
                .filter(row -> row.getStorageUri() != null && row.getStorageUri().startsWith(uriPrefix))
                .findFirst()
                .orElse(null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "chat");
        meta.put("sessionId", String.valueOf(sessionId));
        meta.put("messageId", extracted.getDisclosureMessageId());
        meta.put("content", extracted.getDisclosureMarkdown());
        String metaJson = JsonUtils.toJsonStr(meta);
        if (existing == null) {
            PkArtifact artifact = new PkArtifact();
            artifact.setMatterId(matter.getId());
            artifact.setArtifactType("disclosure_md");
            artifact.setName("交底草稿（对话导入）");
            artifact.setStorageUri(storageUri);
            artifact.setMime("text/markdown");
            artifact.setMetaJson(metaJson);
            artifactMapper.insert(artifact);
            return;
        }
        existing.setStorageUri(storageUri);
        existing.setMetaJson(metaJson);
        existing.setName("交底草稿（对话导入）");
        artifactMapper.updateById(existing);
    }

    private void ensureImportedVersion(Long matterId, Long sessionId) {
        if (!listVersions(matterId).isEmpty()) {
            return;
        }
        PkMatterVersion version = new PkMatterVersion();
        version.setMatterId(matterId);
        version.setVersionNo(1);
        version.setLabel("对话导入");
        version.setPathRel("chat/" + sessionId);
        version.setNote("从会话 " + sessionId + " 同步");
        versionMapper.insert(version);
    }

    private SessionRow requireSession(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        Long tenantId = TenantUtils.getCurrentTenantId();
        List<SessionRow> rows = jdbcTemplate.query(
                "SELECT id, title, agent_id, message_table, tenant_id FROM chat_session WHERE id = ?",
                (rs, i) -> new SessionRow(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getObject("agent_id") == null ? null : rs.getLong("agent_id"),
                        rs.getString("message_table"),
                        rs.getLong("tenant_id")),
                sessionId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("会话不存在");
        }
        SessionRow row = rows.getFirst();
        if (tenantId != null && row.tenantId() != tenantId) {
            throw new IllegalArgumentException("会话不存在");
        }
        return row;
    }

    private List<ChatMessage> loadMessages(Long sessionId, String messageTable) {
        String table = "chat_message";
        if (StringUtils.hasText(messageTable) && messageTable.matches("chat_message_\\d{6}")) {
            table = messageTable;
        }
        return jdbcTemplate.query(
                "SELECT id, tenant_id, session_id, role, content, parent_id, path, depth, created_at FROM "
                        + table + " WHERE session_id = ? ORDER BY COALESCE(depth, id) ASC, id ASC",
                this::mapMessage,
                sessionId);
    }

    private ChatMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        ChatMessage msg = new ChatMessage();
        msg.setId(rs.getInt("id"));
        msg.setTenantId(rs.getLong("tenant_id"));
        msg.setSessionId(rs.getLong("session_id"));
        msg.setRole(rs.getString("role"));
        msg.setContent(rs.getString("content"));
        msg.setParentId((Integer) rs.getObject("parent_id"));
        msg.setPath(rs.getString("path"));
        msg.setDepth((Integer) rs.getObject("depth"));
        var ts = rs.getTimestamp("created_at");
        if (ts != null) {
            msg.setCreatedAt(ts.toLocalDateTime());
        }
        return msg;
    }

    private ChatSessionVO mapSession(ResultSet rs, int rowNum) throws SQLException {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(rs.getLong("id"));
        vo.setUserId(rs.getLong("user_id"));
        vo.setAgentId(rs.getLong("agent_id"));
        Object current = rs.getObject("current_message_id");
        if (current instanceof Number n) {
            vo.setCurrentMessageId(n.longValue());
        }
        vo.setTitle(rs.getString("title"));
        vo.setIsPinned(rs.getBoolean("is_pinned"));
        var pin = rs.getTimestamp("pin_time");
        if (pin != null) {
            vo.setPinTime(pin.toLocalDateTime());
        }
        var created = rs.getTimestamp("created_at");
        if (created != null) {
            vo.setCreatedAt(created.toLocalDateTime());
        }
        var updated = rs.getTimestamp("updated_at");
        if (updated != null) {
            vo.setUpdatedAt(updated.toLocalDateTime());
        }
        vo.setMessageTable(rs.getString("message_table"));
        return vo;
    }

    private PkMatter requireMatter(Long matterId) {
        PkMatter matter = getById(matterId);
        if (matter == null) {
            throw new IllegalArgumentException("案件不存在");
        }
        return matter;
    }

    private record SessionRow(long id, String title, Long agentId, String messageTable, long tenantId) {
    }
}
