package com.hxh.apboa.common.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 专利点资产清单提交 / 确认。
 */
@Data
public class PkPatentPointsDTO {

    private List<Item> inventory = new ArrayList<>();
    private List<String> selectedIds = new ArrayList<>();
    private List<String> tradeSecretIds = new ArrayList<>();
    private List<String> deferredIds = new ArrayList<>();
    private String notes;

    @Data
    public static class Item {
        private String id;
        private String title;
        /** file | secret | defer */
        private String bucket;
        private String summary;
        private String priorArtNote;
    }
}
