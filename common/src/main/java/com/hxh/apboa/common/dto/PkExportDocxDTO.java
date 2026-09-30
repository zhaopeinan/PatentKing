package com.hxh.apboa.common.dto;

import lombok.Data;

@Data
public class PkExportDocxDTO {
    /** 可选：直接传 Markdown；空则用案件最新 disclosure_md 产物 */
    private String markdown;
    /** 输出文件名前缀 */
    private String title;
    /** png | auto */
    private String diagramMode;
    /** 若已有 sidecar export_id，则直接拉取登记，不再渲染 */
    private String exportId;
}
