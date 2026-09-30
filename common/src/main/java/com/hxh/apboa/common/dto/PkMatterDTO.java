package com.hxh.apboa.common.dto;

import com.hxh.apboa.common.mp.annotation.QueryDefine;
import com.hxh.apboa.common.mp.support.PageParams;
import com.hxh.apboa.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PkMatterDTO extends PageParams {

    @QueryDefine(value = "标题", condition = QueryCondition.LIKE)
    private String title;

    @QueryDefine(value = "类型", condition = QueryCondition.EQ)
    private String matterType;

    @QueryDefine(value = "状态", condition = QueryCondition.EQ)
    private String status;
}
