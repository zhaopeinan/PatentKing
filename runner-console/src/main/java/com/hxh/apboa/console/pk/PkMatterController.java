package com.hxh.apboa.console.pk;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hxh.apboa.common.config.auth.RoleNeed;
import com.hxh.apboa.common.dto.PkChatSessionBindDTO;
import com.hxh.apboa.common.dto.PkDeliveryDTO;
import com.hxh.apboa.common.dto.PkExportDocxDTO;
import com.hxh.apboa.common.dto.PkExportSessionDTO;
import com.hxh.apboa.common.dto.PkMatterDTO;
import com.hxh.apboa.common.dto.PkPatentPointsDTO;
import com.hxh.apboa.common.entity.PkArtifact;
import com.hxh.apboa.common.entity.PkMatter;
import com.hxh.apboa.common.entity.PkMatterVersion;
import com.hxh.apboa.common.enums.TenantRole;
import com.hxh.apboa.common.mp.support.MP;
import com.hxh.apboa.common.mp.support.PageParams;
import com.hxh.apboa.common.r.R;
import com.hxh.apboa.common.util.BeanUtils;
import com.hxh.apboa.common.vo.ChatSessionVO;
import com.hxh.apboa.common.vo.PkArtifactVO;
import com.hxh.apboa.common.vo.PkMatterVO;
import com.hxh.apboa.common.vo.PkMatterVersionVO;
import com.hxh.apboa.pk.service.PkMatterService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/pk/matter")
@RequiredArgsConstructor
public class PkMatterController {

    private final PkMatterService pkMatterService;

    @GetMapping("/page")
    public R<IPage<PkMatterVO>> page(PageParams pageParams, PkMatterDTO query) {
        IPage<PkMatter> page;
        if (query != null && "disclosure".equals(query.getMatterType())) {
            // 「交底撰写」筛选包含历史细分类
            query.setMatterType(null);
            var qw = MP.<PkMatter>getQueryWrapper(query);
            qw.in("matter_type", com.hxh.apboa.pk.PkMatterAgentCodes.disclosureFamilyTypes());
            page = pkMatterService.page(MP.getPage(pageParams), qw);
        } else {
            page = pkMatterService.page(MP.getPage(pageParams), MP.getQueryWrapper(query));
        }
        return R.data(BeanUtils.copyPage(page, PkMatterVO.class));
    }

    @GetMapping("/{id}")
    public R<PkMatterVO> detail(@PathVariable("id") Long id) {
        PkMatter matter = pkMatterService.getById(id);
        if (matter == null) {
            return R.fail("案件不存在");
        }
        return R.data(BeanUtils.copy(matter, PkMatterVO.class));
    }

    @PostMapping("/{id}/patent-points")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkMatterVO> submitPatentPoints(@PathVariable("id") Long id, @RequestBody(required = false) PkPatentPointsDTO dto) {
        return R.data(BeanUtils.copy(pkMatterService.submitPatentPoints(id, dto), PkMatterVO.class));
    }

    @PostMapping("/{id}/patent-points/confirm")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkMatterVO> confirmPatentPoints(@PathVariable("id") Long id, @RequestBody(required = false) PkPatentPointsDTO dto) {
        return R.data(BeanUtils.copy(pkMatterService.confirmPatentPoints(id, dto), PkMatterVO.class));
    }

    @PostMapping("/{id}/delivery")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkMatterVO> submitDelivery(@PathVariable("id") Long id, @RequestBody(required = false) PkDeliveryDTO dto) {
        return R.data(BeanUtils.copy(pkMatterService.submitDelivery(id, dto), PkMatterVO.class));
    }

    @PostMapping("/{id}/bind-session")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkMatterVO> bindSession(@PathVariable("id") Long id, @RequestBody PkChatSessionBindDTO dto) {
        Long sessionId = dto == null ? null : dto.getSessionId();
        return R.data(BeanUtils.copy(pkMatterService.bindChatSession(id, sessionId), PkMatterVO.class));
    }

    @PostMapping("/{id}/import-session")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkMatterVO> importSession(@PathVariable("id") Long id, @RequestBody PkChatSessionBindDTO dto) {
        Long sessionId = dto == null ? null : dto.getSessionId();
        return R.data(BeanUtils.copy(pkMatterService.importChatSession(id, sessionId), PkMatterVO.class));
    }

    @GetMapping("/{id}/candidate-sessions")
    public R<List<ChatSessionVO>> candidateSessions(@PathVariable("id") Long id) {
        return R.data(pkMatterService.listCandidateSessions(id));
    }

    @PostMapping("/{id}/export-docx")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkArtifactVO> exportDocx(@PathVariable("id") Long id, @RequestBody(required = false) PkExportDocxDTO dto) {
        try {
            return R.data(BeanUtils.copy(pkMatterService.exportDocx(id, dto), PkArtifactVO.class));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/export-session")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<java.util.Map<String, Object>> createExportSession(
            @PathVariable("id") Long id,
            @RequestBody(required = false) PkExportSessionDTO dto) {
        try {
            return R.data(pkMatterService.createExportSession(id, dto));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/export-session/{sessionId}/figure/{index}")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<java.util.Map<String, Object>> renderExportFigure(
            @PathVariable("id") Long id,
            @PathVariable("sessionId") String sessionId,
            @PathVariable("index") int index,
            @RequestBody(required = false) PkExportSessionDTO dto) {
        try {
            return R.data(pkMatterService.renderExportFigure(id, sessionId, index, dto));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/export-session/{sessionId}/finalize")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkArtifactVO> finalizeExportSession(
            @PathVariable("id") Long id,
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) PkExportSessionDTO dto) {
        try {
            return R.data(BeanUtils.copy(pkMatterService.finalizeExportSession(id, sessionId, dto), PkArtifactVO.class));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/bind-default-agent")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkMatterVO> bindDefaultAgent(@PathVariable("id") Long id) {
        PkMatter matter = pkMatterService.getById(id);
        if (matter == null) {
            return R.data(null);
        }
        String scene = com.hxh.apboa.pk.PkMatterSceneMeta.readScene(matter.getMetaJson());
        if (!org.springframework.util.StringUtils.hasText(scene)) {
            scene = com.hxh.apboa.pk.PkMatterAgentCodes.inferSceneFromLegacyType(matter.getMatterType());
        }
        Long agentId = pkMatterService.resolveDefaultAgentId(matter.getMatterType(), scene);
        if (agentId != null) {
            matter.setAgentDefinitionId(agentId);
            pkMatterService.updateMatter(matter);
        }
        return R.data(BeanUtils.copy(pkMatterService.getById(id), PkMatterVO.class));
    }

    @PostMapping
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<Boolean> save(@RequestBody PkMatter entity) {
        return R.data(pkMatterService.createMatter(entity));
    }

    @PutMapping
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<Boolean> update(@RequestBody PkMatter entity) {
        return R.data(pkMatterService.updateMatter(entity));
    }

    @DeleteMapping
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(pkMatterService.deleteMatters(ids));
    }

    @GetMapping("/{id}/versions")
    public R<List<PkMatterVersionVO>> versions(@PathVariable("id") Long id) {
        return R.data(BeanUtils.copyList(pkMatterService.listVersions(id), PkMatterVersionVO.class));
    }

    @PostMapping("/{id}/versions")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkMatterVersionVO> createVersion(@PathVariable("id") Long id, @RequestBody PkMatterVersion entity) {
        entity.setMatterId(id);
        return R.data(BeanUtils.copy(pkMatterService.createVersion(entity), PkMatterVersionVO.class));
    }

    @GetMapping("/{id}/artifacts")
    public R<List<PkArtifactVO>> artifacts(@PathVariable("id") Long id) {
        return R.data(BeanUtils.copyList(pkMatterService.listArtifacts(id), PkArtifactVO.class));
    }

    @GetMapping("/{id}/artifacts/{artifactId}/download")
    public void downloadArtifact(
            @PathVariable("id") Long id,
            @PathVariable("artifactId") Long artifactId,
            HttpServletResponse response) {
        try {
            var file = pkMatterService.downloadArtifact(id, artifactId);
            String filename = StringUtils.hasText(file.getFilename()) ? file.getFilename() : "artifact.bin";
            String mime = StringUtils.hasText(file.getMime())
                    ? file.getMime()
                    : MediaType.APPLICATION_OCTET_STREAM_VALUE;
            response.setContentType(mime);
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
            response.setContentLength(file.getBytes() == null ? 0 : file.getBytes().length);
            try (OutputStream out = response.getOutputStream()) {
                out.write(file.getBytes() == null ? new byte[0] : file.getBytes());
                out.flush();
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            response.setStatus(400);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            try {
                response.getWriter().write("{\"success\":false,\"msg\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            } catch (IOException ignored) {
                // ignore
            }
        } catch (IOException e) {
            throw new RuntimeException("产物下载失败", e);
        }
    }

    @PostMapping("/{id}/artifacts")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<PkArtifactVO> createArtifact(@PathVariable("id") Long id, @RequestBody PkArtifact entity) {
        entity.setMatterId(id);
        return R.data(BeanUtils.copy(pkMatterService.createArtifact(entity), PkArtifactVO.class));
    }
}
