package com.hxh.apboa.common.dto;

import lombok.Data;

@Data
public class PkExportSessionDTO {
    /** 可选：直接传 Markdown；空则用案件最新 disclosure_md */
    private String markdown;
    /** png | auto */
    private String diagramMode;
    /** 输出文件名前缀（finalize 用） */
    private String title;
    /** 强制重渲已成功的图 */
    private Boolean force;
}
