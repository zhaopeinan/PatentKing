package com.hxh.apboa.pk.service;

import com.hxh.apboa.common.dto.PkTokenlabSettingsDTO;
import com.hxh.apboa.common.vo.PkTokenlabSettingsVO;

public interface PkSettingsService {

    PkTokenlabSettingsVO getTokenlabSettings();

    PkTokenlabSettingsVO saveTokenlabSettings(PkTokenlabSettingsDTO dto);

    boolean isTokenlabConfigured();
}
