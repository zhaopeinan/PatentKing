package com.hxh.apboa.pk.service.impl;

import com.hxh.apboa.common.dto.PkTokenlabSettingsDTO;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.common.vo.PkTokenlabSettingsVO;
import com.hxh.apboa.pk.PkTokenlabSettingsStore;
import com.hxh.apboa.pk.service.PkSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PkSettingsServiceImpl implements PkSettingsService {

    private final PkTokenlabSettingsStore tokenlabStore;

    @Override
    public PkTokenlabSettingsVO getTokenlabSettings() {
        return tokenlabStore.getForTenant(requireTenantId());
    }

    @Override
    public PkTokenlabSettingsVO saveTokenlabSettings(PkTokenlabSettingsDTO dto) {
        return tokenlabStore.saveForTenant(requireTenantId(), dto);
    }

    @Override
    public boolean isTokenlabConfigured() {
        return tokenlabStore.isConfigured(requireTenantId());
    }

    private static Long requireTenantId() {
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("未登录或未选择租户");
        }
        return tenantId;
    }
}
