package com.hxh.apboa.console.pk;

import com.hxh.apboa.agent.service.AgentDefinitionService;
import com.hxh.apboa.common.config.auth.RoleNeed;
import com.hxh.apboa.common.entity.AgentDefinition;
import com.hxh.apboa.common.entity.ModelConfig;
import com.hxh.apboa.common.entity.SkillPackage;
import com.hxh.apboa.common.entity.StorageProtocol;
import com.hxh.apboa.common.entity.SystemPromptTemplate;
import com.hxh.apboa.common.entity.ToolConfig;
import com.hxh.apboa.common.enums.AgentType;
import com.hxh.apboa.common.enums.CodeLanguage;
import com.hxh.apboa.common.enums.ScopeType;
import com.hxh.apboa.common.enums.TenantRole;
import com.hxh.apboa.common.enums.ToolChoiceStrategy;
import com.hxh.apboa.common.enums.ToolType;
import com.hxh.apboa.common.r.R;
import com.hxh.apboa.common.util.JsonUtils;
import com.hxh.apboa.common.vo.AgentDefinitionVO;
import com.hxh.apboa.model.service.ModelConfigService;
import com.hxh.apboa.pk.PkAlignDisclosureToolCode;
import com.hxh.apboa.pk.PkConfirmPatentPointsToolCode;
import com.hxh.apboa.pk.PkExportDisclosureToolCode;
import com.hxh.apboa.pk.PkMatterAgentCodes;
import com.hxh.apboa.pk.PkPriorArtToolCode;
import com.hxh.apboa.pk.PkPromptTemplates;
import com.hxh.apboa.pk.service.PkMatterService;
import com.hxh.apboa.prompt.service.SystemPromptTemplateService;
import com.hxh.apboa.skill.service.SkillPackageService;
import com.hxh.apboa.tool.service.AgentToolService;
import com.hxh.apboa.tool.service.ToolService;
import com.hxh.apboa.resource.service.StorageProtocolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pk/bootstrap")
@RequiredArgsConstructor
public class PkBootstrapController {

    private final AgentDefinitionService agentDefinitionService;
    private final SkillPackageService skillPackageService;
    private final ModelConfigService modelConfigService;
    private final PkMatterService pkMatterService;
    private final SystemPromptTemplateService systemPromptTemplateService;
    private final ToolService toolService;
    private final AgentToolService agentToolService;
    private final StorageProtocolService storageProtocolService;

    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skills", skillPackageService.lambdaQuery()
                .select(SkillPackage::getId, SkillPackage::getName, SkillPackage::getCategory)
                .list()
                .stream()
                .map(s -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(s.getId()));
                    row.put("name", s.getName() == null ? "" : s.getName());
                    return row;
                })
                .toList());
        Map<String, Long> agents = new LinkedHashMap<>();
        agents.put(PkMatterAgentCodes.DISCLOSURE, lookupByCode(PkMatterAgentCodes.DISCLOSURE));
        agents.put(PkMatterAgentCodes.PAPER2PATENT, lookupByCode(PkMatterAgentCodes.PAPER2PATENT));
        agents.put(PkMatterAgentCodes.RADAR, lookupByCode(PkMatterAgentCodes.RADAR));
        agents.put(PkMatterAgentCodes.DISCLOSURE_LITE, lookupByCode(PkMatterAgentCodes.DISCLOSURE_LITE));
        data.put("agents", agents);
        data.put("hasModel", modelConfigService.lambdaQuery().last("LIMIT 1").one() != null);
        data.put("hasStorage", countValidStorage() == 1);
        return R.data(data);
    }

    @PostMapping("/agents")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_EDITOR})
    public R<Map<String, Long>> ensureAgents() {
        ensureLocalStorage();
        Long modelId = firstModelId();
        Long disclosurePrompt = ensurePrompt(
                PkPromptTemplates.DISCLOSURE_NAME,
                PkPromptTemplates.DISCLOSURE_DESC,
                PkPromptTemplates.DISCLOSURE_CONTENT);
        Long paperPrompt = ensurePrompt(
                PkPromptTemplates.PAPER2PATENT_NAME,
                PkPromptTemplates.PAPER2PATENT_DESC,
                PkPromptTemplates.PAPER2PATENT_CONTENT);
        Long radarPrompt = ensurePrompt(
                PkPromptTemplates.RADAR_NAME,
                PkPromptTemplates.RADAR_DESC,
                PkPromptTemplates.RADAR_CONTENT);
        Long litePrompt = ensurePrompt(
                PkPromptTemplates.DISCLOSURE_LITE_NAME,
                PkPromptTemplates.DISCLOSURE_LITE_DESC,
                PkPromptTemplates.DISCLOSURE_LITE_CONTENT);
        Long priorArtToolId = ensurePriorArtTool();
        Long confirmToolId = ensureConfirmPatentPointsTool();
        Long alignToolId = ensureAlignDisclosureTool();
        Long exportToolId = ensureExportDisclosureTool();

        Map<String, Long> created = new LinkedHashMap<>();
        created.put(PkMatterAgentCodes.DISCLOSURE, ensureAgent(
                PkMatterAgentCodes.DISCLOSURE,
                PkPromptTemplates.DISCLOSURE_NAME,
                "专利点挖掘、查新与交底书成文。须先出资产清单并等人确认。",
                List.of("patent-disclosure-skill", "patent-mining-disclosure-skill"),
                modelId,
                disclosurePrompt,
                List.of(priorArtToolId, confirmToolId, alignToolId, exportToolId),
                PkPromptTemplates.DISCLOSURE_CONTENT));
        created.put(PkMatterAgentCodes.PAPER2PATENT, ensureAgent(
                PkMatterAgentCodes.PAPER2PATENT,
                PkPromptTemplates.PAPER2PATENT_NAME,
                "将学术论文转为中国发明专利申请文本，忠实原文、不编造。",
                List.of("paper2patent"),
                modelId,
                paperPrompt,
                List.of(priorArtToolId, exportToolId),
                PkPromptTemplates.PAPER2PATENT_CONTENT));
        created.put(PkMatterAgentCodes.RADAR, ensureAgent(
                PkMatterAgentCodes.RADAR,
                PkPromptTemplates.RADAR_NAME,
                "输入完整公开号，产出可复核的竞品 claim chart。",
                List.of("patentradar"),
                modelId,
                radarPrompt,
                List.of(priorArtToolId),
                PkPromptTemplates.RADAR_CONTENT));
        created.put(PkMatterAgentCodes.DISCLOSURE_LITE, ensureAgent(
                PkMatterAgentCodes.DISCLOSURE_LITE,
                PkPromptTemplates.DISCLOSURE_LITE_NAME,
                "多轮对话引导发明人从零写交底。",
                List.of("patent-disclosure"),
                modelId,
                litePrompt,
                List.of(priorArtToolId, confirmToolId, alignToolId, exportToolId),
                PkPromptTemplates.DISCLOSURE_LITE_CONTENT));
        return R.data(created);
    }

    @GetMapping("/resolve")
    public R<Long> resolve(
            @RequestParam("matterType") String matterType,
            @RequestParam(value = "scene", required = false) String scene) {
        return R.data(pkMatterService.resolveDefaultAgentId(matterType, scene));
    }

    private long countValidStorage() {
        return storageProtocolService.lambdaQuery()
                .eq(StorageProtocol::getValid, 1)
                .count();
    }

    /**
     * 对话传附件要求恰好一条 valid=1 的存储协议。本地 Docker 默认写到已挂载目录。
     */
    private void ensureLocalStorage() {
        long validCount = countValidStorage();
        if (validCount == 1) {
            return;
        }
        if (validCount > 1) {
            List<StorageProtocol> valids = storageProtocolService.lambdaQuery()
                    .eq(StorageProtocol::getValid, 1)
                    .orderByAsc(StorageProtocol::getId)
                    .list();
            for (int i = 1; i < valids.size(); i++) {
                StorageProtocol extra = valids.get(i);
                extra.setValid(0);
                storageProtocolService.updateById(extra);
            }
            return;
        }
        StorageProtocol created = new StorageProtocol();
        created.setName("本地存储");
        created.setProtocol("LOCAL");
        created.setProtocolConfig("{\"localDir\":\"/app/.apboa/storage\"}");
        created.setValid(1);
        created.setRemark("PatentKing 默认本地存储");
        storageProtocolService.save(created);
    }

    private Long firstModelId() {
        ModelConfig connected = modelConfigService.lambdaQuery()
                .eq(ModelConfig::getEnabled, true)
                .eq(ModelConfig::getConnectivityStatus, "CONNECTED")
                .last("LIMIT 1")
                .one();
        if (connected != null) {
            return connected.getId();
        }
        ModelConfig model = modelConfigService.lambdaQuery()
                .eq(ModelConfig::getEnabled, true)
                .last("LIMIT 1")
                .one();
        return model == null ? null : model.getId();
    }

    private Long ensurePriorArtTool() {
        ToolConfig existing = toolService.lambdaQuery()
                .eq(ToolConfig::getToolId, PkPriorArtToolCode.TOOL_ID)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            existing.setName(PkPriorArtToolCode.NAME);
            existing.setDescription(PkPriorArtToolCode.DESC);
            existing.setCode(PkPriorArtToolCode.SOURCE);
            existing.setEnabled(true);
            existing.setInputSchema(JsonUtils.parse("""
                    [
                      {"name":"query","description":"检索关键词或一个技术语义块，每次只传一个","type":"string","required":true,"defaultValue":""},
                      {"name":"limit","description":"返回条数，默认 8，最大 20","type":"integer","required":false,"defaultValue":"8"}
                    ]
                    """));
            toolService.updateById(existing);
            return existing.getId();
        }
        ToolConfig created = new ToolConfig();
        created.setName(PkPriorArtToolCode.NAME);
        created.setToolId(PkPriorArtToolCode.TOOL_ID);
        created.setDescription(PkPriorArtToolCode.DESC);
        created.setCategory(PkPromptTemplates.CATEGORY);
        created.setToolType(ToolType.CUSTOM);
        created.setNeedConfirm(false);
        created.setLanguage(CodeLanguage.JAVA);
        created.setCode(PkPriorArtToolCode.SOURCE);
        created.setVersion("1.0");
        created.setScopeType(ScopeType.TENANT);
        created.setEnabled(true);
        created.setInputSchema(JsonUtils.parse("""
                [
                  {"name":"query","description":"检索关键词或一个技术语义块，每次只传一个","type":"string","required":true,"defaultValue":""},
                  {"name":"limit","description":"返回条数，默认 8，最大 20","type":"integer","required":false,"defaultValue":"8"}
                ]
                """));
        toolService.save(created);
        return created.getId();
    }

    private Long ensureConfirmPatentPointsTool() {
        ToolConfig existing = toolService.lambdaQuery()
                .eq(ToolConfig::getToolId, PkConfirmPatentPointsToolCode.TOOL_ID)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            existing.setName(PkConfirmPatentPointsToolCode.NAME);
            existing.setDescription(PkConfirmPatentPointsToolCode.DESC);
            existing.setCode(PkConfirmPatentPointsToolCode.SOURCE);
            existing.setNeedConfirm(true);
            existing.setEnabled(true);
            existing.setInputSchema(JsonUtils.parse(PkConfirmPatentPointsToolCode.INPUT_SCHEMA));
            toolService.updateById(existing);
            return existing.getId();
        }
        ToolConfig created = new ToolConfig();
        created.setName(PkConfirmPatentPointsToolCode.NAME);
        created.setToolId(PkConfirmPatentPointsToolCode.TOOL_ID);
        created.setDescription(PkConfirmPatentPointsToolCode.DESC);
        created.setCategory(PkPromptTemplates.CATEGORY);
        created.setToolType(ToolType.CUSTOM);
        created.setNeedConfirm(true);
        created.setLanguage(CodeLanguage.JAVA);
        created.setCode(PkConfirmPatentPointsToolCode.SOURCE);
        created.setVersion("1.0");
        created.setScopeType(ScopeType.TENANT);
        created.setEnabled(true);
        created.setInputSchema(JsonUtils.parse(PkConfirmPatentPointsToolCode.INPUT_SCHEMA));
        toolService.save(created);
        return created.getId();
    }

    private Long ensureAlignDisclosureTool() {
        ToolConfig existing = toolService.lambdaQuery()
                .eq(ToolConfig::getToolId, PkAlignDisclosureToolCode.TOOL_ID)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            existing.setName(PkAlignDisclosureToolCode.NAME);
            existing.setDescription(PkAlignDisclosureToolCode.DESC);
            existing.setCode(PkAlignDisclosureToolCode.SOURCE);
            existing.setNeedConfirm(false);
            existing.setEnabled(true);
            existing.setInputSchema(JsonUtils.parse(PkAlignDisclosureToolCode.INPUT_SCHEMA));
            toolService.updateById(existing);
            return existing.getId();
        }
        ToolConfig created = new ToolConfig();
        created.setName(PkAlignDisclosureToolCode.NAME);
        created.setToolId(PkAlignDisclosureToolCode.TOOL_ID);
        created.setDescription(PkAlignDisclosureToolCode.DESC);
        created.setCategory(PkPromptTemplates.CATEGORY);
        created.setToolType(ToolType.CUSTOM);
        created.setNeedConfirm(false);
        created.setLanguage(CodeLanguage.JAVA);
        created.setCode(PkAlignDisclosureToolCode.SOURCE);
        created.setVersion("1.0");
        created.setScopeType(ScopeType.TENANT);
        created.setEnabled(true);
        created.setInputSchema(JsonUtils.parse(PkAlignDisclosureToolCode.INPUT_SCHEMA));
        toolService.save(created);
        return created.getId();
    }

    private Long ensureExportDisclosureTool() {
        ToolConfig existing = toolService.lambdaQuery()
                .eq(ToolConfig::getToolId, PkExportDisclosureToolCode.TOOL_ID)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            existing.setName(PkExportDisclosureToolCode.NAME);
            existing.setDescription(PkExportDisclosureToolCode.DESC);
            existing.setCode(PkExportDisclosureToolCode.SOURCE);
            existing.setNeedConfirm(false);
            existing.setEnabled(true);
            existing.setInputSchema(JsonUtils.parse(PkExportDisclosureToolCode.INPUT_SCHEMA));
            toolService.updateById(existing);
            return existing.getId();
        }
        ToolConfig created = new ToolConfig();
        created.setName(PkExportDisclosureToolCode.NAME);
        created.setToolId(PkExportDisclosureToolCode.TOOL_ID);
        created.setDescription(PkExportDisclosureToolCode.DESC);
        created.setCategory(PkPromptTemplates.CATEGORY);
        created.setToolType(ToolType.CUSTOM);
        created.setNeedConfirm(false);
        created.setLanguage(CodeLanguage.JAVA);
        created.setCode(PkExportDisclosureToolCode.SOURCE);
        created.setVersion("1.0");
        created.setScopeType(ScopeType.TENANT);
        created.setEnabled(true);
        created.setInputSchema(JsonUtils.parse(PkExportDisclosureToolCode.INPUT_SCHEMA));
        toolService.save(created);
        return created.getId();
    }

    private Long ensurePrompt(String name, String description, String content) {
        SystemPromptTemplate existing = systemPromptTemplateService.lambdaQuery()
                .eq(SystemPromptTemplate::getName, name)
                .eq(SystemPromptTemplate::getCategory, PkPromptTemplates.CATEGORY)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            existing.setDescription(description);
            existing.setContent(content);
            existing.setEnabled(true);
            systemPromptTemplateService.updateById(existing);
            return existing.getId();
        }
        SystemPromptTemplate created = new SystemPromptTemplate();
        created.setCategory(PkPromptTemplates.CATEGORY);
        created.setName(name);
        created.setDescription(description);
        created.setContent(content);
        created.setUsageCount(0);
        created.setEnabled(true);
        systemPromptTemplateService.save(created);
        return created.getId();
    }

    private Long ensureAgent(String code, String name, String description, List<String> skillNames,
                             Long modelId, Long promptId, List<Long> toolIds, String systemPrompt) {
        Long byCode = lookupByCode(code);
        if (byCode != null) {
            bindExistingAgent(byCode, promptId, systemPrompt, modelId, toolIds);
            return byCode;
        }

        List<Long> skillIds = new ArrayList<>();
        for (String skillName : skillNames) {
            SkillPackage pkg = skillPackageService.lambdaQuery()
                    .eq(SkillPackage::getName, skillName)
                    .last("LIMIT 1")
                    .one();
            if (pkg != null) {
                skillIds.add(pkg.getId());
            }
        }

        List<Long> tools = new ArrayList<>();
        if (toolIds != null) {
            for (Long toolId : toolIds) {
                if (toolId != null) {
                    tools.add(toolId);
                }
            }
        }

        AgentDefinitionVO vo = new AgentDefinitionVO();
        vo.setAgentType(AgentType.CUSTOM);
        vo.setName(name);
        vo.setAgentCode(code);
        vo.setDescription(description);
        vo.setModelConfigId(modelId);
        vo.setSkill(skillIds);
        vo.setHook(List.of());
        vo.setTool(tools);
        vo.setWorkflow(List.of());
        vo.setMcp(List.of());
        vo.setMcpBindings(List.of());
        vo.setSubAgent(List.of());
        vo.setKnowledgeBase(List.of());
        vo.setToolChoiceStrategy(ToolChoiceStrategy.AUTO);
        vo.setSystemPromptTemplateId(promptId);
        vo.setSystemPrompt(systemPrompt);
        vo.setFollowTemplate(true);
        vo.setMaxIterations(40);
        vo.setEnablePlanning(false);
        vo.setShowToolProcess(true);
        vo.setEnableMemory(true);
        vo.setSensitiveFilterEnabled(false);
        vo.setTag("pk");
        vo.setEnabled(true);
        vo.setVersion("1.0");
        agentDefinitionService.saveAgentDefinition(vo);
        return vo.getId();
    }

    private void bindExistingAgent(Long agentId, Long promptId, String systemPrompt, Long modelId, List<Long> toolIds) {
        if (agentId == null) {
            return;
        }
        AgentDefinition agent = agentDefinitionService.getById(agentId);
        if (agent == null) {
            return;
        }
        boolean dirty = false;
        if (promptId != null && !promptId.equals(agent.getSystemPromptTemplateId())) {
            agent.setSystemPromptTemplateId(promptId);
            dirty = true;
        }
        if (agent.getFollowTemplate() == null || !agent.getFollowTemplate()) {
            agent.setFollowTemplate(true);
            dirty = true;
        }
        if (systemPrompt != null && !systemPrompt.equals(agent.getSystemPrompt())) {
            agent.setSystemPrompt(systemPrompt);
            dirty = true;
        }
        if (modelId != null && agent.getModelConfigId() == null) {
            agent.setModelConfigId(modelId);
            dirty = true;
        }
        if (agent.getShowToolProcess() == null || !Boolean.TRUE.equals(agent.getShowToolProcess())) {
            agent.setShowToolProcess(true);
            dirty = true;
        }
        if (dirty) {
            agentDefinitionService.updateById(agent);
        }
        if (toolIds != null && !toolIds.isEmpty()) {
            List<Long> current = agentToolService.getToolIds(agentId);
            List<Long> tools = new ArrayList<>(current == null ? List.of() : current);
            boolean changed = false;
            for (Long toolId : toolIds) {
                if (toolId != null && !tools.contains(toolId)) {
                    tools.add(toolId);
                    changed = true;
                }
            }
            if (changed) {
                agentToolService.saveAgentTool(agentId, tools);
            }
        }
    }

    private Long lookupByCode(String code) {
        var one = agentDefinitionService.lambdaQuery()
                .eq(com.hxh.apboa.common.entity.AgentDefinition::getAgentCode, code)
                .last("LIMIT 1")
                .one();
        return one == null ? null : one.getId();
    }
}
