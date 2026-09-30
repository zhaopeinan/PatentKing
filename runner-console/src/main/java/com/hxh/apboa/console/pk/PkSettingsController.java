package com.hxh.apboa.console.pk;

import com.hxh.apboa.common.config.auth.RoleNeed;
import com.hxh.apboa.common.dto.PkTokenlabSettingsDTO;
import com.hxh.apboa.common.enums.TenantRole;
import com.hxh.apboa.common.r.R;
import com.hxh.apboa.common.vo.PkTokenlabSettingsVO;
import com.hxh.apboa.pk.service.PkSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pk/settings")
@RequiredArgsConstructor
public class PkSettingsController {

    private final PkSettingsService pkSettingsService;

    @GetMapping("/tokenlab")
    public R<PkTokenlabSettingsVO> getTokenlab() {
        return R.data(pkSettingsService.getTokenlabSettings());
    }

    @PutMapping("/tokenlab")
    @RoleNeed({TenantRole.TENANT_ADMIN, TenantRole.TENANT_OWNER})
    public R<PkTokenlabSettingsVO> saveTokenlab(@RequestBody PkTokenlabSettingsDTO dto) {
        return R.data(pkSettingsService.saveTokenlabSettings(dto));
    }

    @GetMapping("/tokenlab/status")
    public R<Boolean> tokenlabStatus() {
        return R.data(pkSettingsService.isTokenlabConfigured());
    }
}
