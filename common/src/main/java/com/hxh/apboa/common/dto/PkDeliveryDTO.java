package com.hxh.apboa.common.dto;

import lombok.Data;

/**
 * 摘要确认与交付格式（UIP pk_delivery_format 提交）。
 */
@Data
public class PkDeliveryDTO {

    /** confirm | adjust */
    private String previewAction;
    private String adjustNotes;
    /** word | markdown */
    private String deliveryFormat;
    /** png | auto */
    private String diagramMode;
    private Boolean includePdf;
}
