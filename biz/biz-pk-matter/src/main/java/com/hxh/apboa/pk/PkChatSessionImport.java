package com.hxh.apboa.pk;

import com.fasterxml.jackson.databind.JsonNode;
import com.hxh.apboa.common.dto.PkPatentPointsDTO;
import com.hxh.apboa.common.entity.ChatMessage;
import com.hxh.apboa.common.util.JsonUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从对话消息中抽出专利点清单、确认状态、发明人、交底正文。
 */
public final class PkChatSessionImport {

    private static final Pattern INVENTOR = Pattern.compile(
            "发明人(?:信息)?[写为是：:\\s]*([\\u4e00-\\u9fa5A-Za-z0-9（）()]{2,40})");

    private PkChatSessionImport() {
    }

    public static Result extract(List<ChatMessage> messages) {
        Result result = new Result();
        if (messages == null || messages.isEmpty()) {
            return result;
        }
        int bestScore = 0;
        for (ChatMessage msg : messages) {
            if (msg == null || !StringUtils.hasText(msg.getContent())) {
                continue;
            }
            String role = msg.getRole() == null ? "" : msg.getRole();
            if ("tool".equals(role)) {
                absorbTool(msg.getContent(), result);
                continue;
            }
            String text = unwrapText(msg.getContent());
            if ("user".equals(role)) {
                absorbInventor(text, result);
                continue;
            }
            if (!"assistant".equals(role)) {
                continue;
            }
            int score = disclosureScore(text);
            if (score > bestScore) {
                bestScore = score;
                result.disclosureMarkdown = text;
                result.disclosureMessageId = msg.getId();
            }
        }
        fillSecretIdsFromInventory(result.points);
        return result;
    }

    private static void absorbTool(String content, Result result) {
        JsonNode root = parseQuiet(content);
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode argsNode = asObject(root.get("args"));
        if (argsNode == null) {
            return;
        }
        boolean confirmTool = false;
        JsonNode name = root.get("name");
        if (name != null && name.isTextual()) {
            confirmTool = "pk_confirm_patent_points".equals(name.asText());
        }
        JsonNode inventoryRaw = argsNode.get("inventory_json");
        if (inventoryRaw == null) {
            inventoryRaw = argsNode.get("inventory");
        }
        List<PkPatentPointsDTO.Item> items = parseInventory(inventoryRaw);
        if (!items.isEmpty()) {
            result.points.setInventory(items);
            confirmTool = true;
        }
        List<String> selected = parseIds(firstText(argsNode, "selected_ids", "selectedIds"));
        if (!selected.isEmpty()) {
            result.points.setSelectedIds(selected);
        }
        List<String> secrets = parseIds(firstText(argsNode, "trade_secret_ids", "tradeSecretIds"));
        if (!secrets.isEmpty()) {
            result.points.setTradeSecretIds(secrets);
        }
        List<String> deferred = parseIds(firstText(argsNode, "deferred_ids", "deferredIds"));
        if (!deferred.isEmpty()) {
            result.points.setDeferredIds(deferred);
        }
        String notes = firstText(argsNode, "notes");
        if (StringUtils.hasText(notes)) {
            result.points.setNotes(notes);
        }
        JsonNode resultNode = asObject(root.get("result"));
        if (resultNode != null) {
            String gate = firstText(resultNode, "gate");
            if (PkPatentPointsMeta.GATE.equals(gate)) {
                result.confirmed = true;
            }
        }
        if (confirmTool && result.points.getInventory() != null && !result.points.getInventory().isEmpty()) {
            result.hasPoints = true;
        }
        if (!result.points.getInventory().isEmpty()) {
            result.hasPoints = true;
        }
    }

    private static void absorbInventor(String text, Result result) {
        if (result.inventor != null || !StringUtils.hasText(text)) {
            return;
        }
        Matcher matcher = INVENTOR.matcher(text);
        if (matcher.find()) {
            result.inventor = matcher.group(1).trim();
        }
    }

    private static void fillSecretIdsFromInventory(PkPatentPointsDTO dto) {
        if (dto.getInventory() == null) {
            return;
        }
        if (dto.getTradeSecretIds() == null) {
            dto.setTradeSecretIds(new ArrayList<>());
        }
        if (dto.getSelectedIds() == null) {
            dto.setSelectedIds(new ArrayList<>());
        }
        if (dto.getDeferredIds() == null) {
            dto.setDeferredIds(new ArrayList<>());
        }
        Set<String> secrets = new LinkedHashSet<>(dto.getTradeSecretIds());
        Set<String> selected = new LinkedHashSet<>(dto.getSelectedIds());
        Set<String> deferred = new LinkedHashSet<>(dto.getDeferredIds());
        boolean fillSelected = selected.isEmpty();
        for (PkPatentPointsDTO.Item item : dto.getInventory()) {
            if (item == null || !StringUtils.hasText(item.getId())) {
                continue;
            }
            String bucket = item.getBucket() == null ? "" : item.getBucket();
            if ("secret".equals(bucket)) {
                secrets.add(item.getId());
            } else if ("defer".equals(bucket)) {
                deferred.add(item.getId());
            } else if ("file".equals(bucket) && fillSelected) {
                selected.add(item.getId());
            }
        }
        dto.setTradeSecretIds(new ArrayList<>(secrets));
        dto.setDeferredIds(new ArrayList<>(deferred));
        if (fillSelected) {
            dto.setSelectedIds(new ArrayList<>(selected));
        }
    }

    static String unwrapText(String content) {
        JsonNode node = parseQuiet(content);
        if (node != null && node.isObject() && node.has("content") && node.get("content").isTextual()
                && !node.has("args") && !node.has("name")) {
            return node.get("content").asText();
        }
        return content == null ? "" : content;
    }

    private static int disclosureScore(String text) {
        if (!StringUtils.hasText(text) || text.length() < 800) {
            return 0;
        }
        if (text.contains("\"interaction\"") && text.contains("pk_patent_points") && text.length() < 4000) {
            return 0;
        }
        int score = text.length();
        if (text.contains("技术领域") || text.contains("背景技术") || text.contains("具体实施方式")
                || text.contains("权利要求") || text.contains("发明名称")) {
            score += 50_000;
        }
        return score;
    }

    private static List<PkPatentPointsDTO.Item> parseInventory(JsonNode raw) {
        JsonNode arr = raw;
        if (raw != null && raw.isTextual()) {
            arr = parseQuiet(raw.asText());
        }
        List<PkPatentPointsDTO.Item> items = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return items;
        }
        int idx = 0;
        for (JsonNode node : arr) {
            idx++;
            if (node == null || !node.isObject()) {
                continue;
            }
            PkPatentPointsDTO.Item item = new PkPatentPointsDTO.Item();
            String id = text(node, "id");
            item.setId(StringUtils.hasText(id) ? id : "P" + idx);
            String title = text(node, "title");
            item.setTitle(StringUtils.hasText(title) ? title : item.getId());
            String bucket = text(node, "bucket");
            item.setBucket(StringUtils.hasText(bucket) ? bucket : "file");
            item.setSummary(text(node, "summary"));
            item.setPriorArtNote(text(node, "priorArtNote"));
            items.add(item);
        }
        return items;
    }

    static List<String> parseIds(String raw) {
        List<String> ids = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return ids;
        }
        String text = raw.trim();
        if (text.startsWith("[")) {
            JsonNode arr = parseQuiet(text);
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n != null && StringUtils.hasText(n.asText())) {
                        ids.add(n.asText().trim());
                    }
                }
                return ids;
            }
        }
        for (String part : text.split("[,，\\s]+")) {
            if (StringUtils.hasText(part)) {
                ids.add(part.trim());
            }
        }
        return ids;
    }

    private static JsonNode asObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return node;
        }
        if (node.isTextual()) {
            JsonNode parsed = parseQuiet(node.asText());
            return parsed != null && parsed.isObject() ? parsed : null;
        }
        return null;
    }

    private static JsonNode parseQuiet(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        String text = json.trim();
        if (!(text.startsWith("{") || text.startsWith("["))) {
            return null;
        }
        try {
            return JsonUtils.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null) {
            return "";
        }
        for (String key : keys) {
            JsonNode n = node.get(key);
            if (n != null && !n.isNull() && StringUtils.hasText(n.asText())) {
                return n.asText().trim();
            }
        }
        return "";
    }

    private static String text(JsonNode node, String key) {
        if (node == null || key == null || !node.has(key) || node.get(key).isNull()) {
            return "";
        }
        return node.get(key).asText("");
    }

    public static final class Result {
        private final PkPatentPointsDTO points = new PkPatentPointsDTO();
        private boolean hasPoints;
        private boolean confirmed;
        private String inventor;
        private Integer disclosureMessageId;
        private String disclosureMarkdown;

        public PkPatentPointsDTO getPoints() {
            return points;
        }

        public boolean isHasPoints() {
            return hasPoints;
        }

        public boolean isConfirmed() {
            return confirmed;
        }

        public String getInventor() {
            return inventor;
        }

        public Integer getDisclosureMessageId() {
            return disclosureMessageId;
        }

        public String getDisclosureMarkdown() {
            return disclosureMarkdown;
        }
    }
}
