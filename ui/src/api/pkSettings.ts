import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { PkTokenlabSettingsDTO, PkTokenlabSettingsVO } from '@/types'

export function getTokenlabSettings() {
  return request.get<ApiResponse<PkTokenlabSettingsVO>>('/api/pk/settings/tokenlab')
}

export function saveTokenlabSettings(data: PkTokenlabSettingsDTO) {
  return request.put<ApiResponse<PkTokenlabSettingsVO>>('/api/pk/settings/tokenlab', data)
}

export function getTokenlabStatus() {
  return request.get<ApiResponse<boolean>>('/api/pk/settings/tokenlab/status')
}
