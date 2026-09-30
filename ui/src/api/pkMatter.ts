import request from '@/utils/request'
import type { ApiResponse, PageResult, PkPatentPointsDTO, PkDeliveryDTO } from '@/types'
import type { ChatSessionVO, PkMatter, PkMatterDTO, PkMatterVO, PkMatterVersionVO, PkArtifactVO } from '@/types'

export function page(query: PkMatterDTO) {
  return request.get<ApiResponse<PageResult<PkMatterVO>>>('/api/pk/matter/page', { params: query })
}

export function detail(id: string) {
  return request.get<ApiResponse<PkMatterVO>>(`/api/pk/matter/${id}`)
}

export function save(entity: PkMatter) {
  return request.post<ApiResponse<boolean>>('/api/pk/matter', entity)
}

export function update(entity: PkMatter) {
  return request.put<ApiResponse<boolean>>('/api/pk/matter', entity)
}

export function remove(ids: string[]) {
  return request.delete<ApiResponse<boolean>>('/api/pk/matter', { data: ids })
}

export function listVersions(id: string) {
  return request.get<ApiResponse<PkMatterVersionVO[]>>(`/api/pk/matter/${id}/versions`)
}

export function createVersion(id: string, body: { label?: string; note?: string }) {
  return request.post<ApiResponse<PkMatterVersionVO>>(`/api/pk/matter/${id}/versions`, body)
}

export function listArtifacts(id: string) {
  return request.get<ApiResponse<PkArtifactVO[]>>(`/api/pk/matter/${id}/artifacts`)
}

export function createArtifact(id: string, body: {
  name: string
  artifactType: string
  storageUri?: string
  mime?: string
  metaJson?: string
  versionId?: string
}) {
  return request.post<ApiResponse<PkArtifactVO>>(`/api/pk/matter/${id}/artifacts`, body)
}

export function bindDefaultAgent(id: string) {
  return request.post<ApiResponse<PkMatterVO>>(`/api/pk/matter/${id}/bind-default-agent`)
}

export function submitPatentPoints(id: string, body: PkPatentPointsDTO) {
  return request.post<ApiResponse<PkMatterVO>>(`/api/pk/matter/${id}/patent-points`, body)
}

export function confirmPatentPoints(id: string, body: PkPatentPointsDTO) {
  return request.post<ApiResponse<PkMatterVO>>(`/api/pk/matter/${id}/patent-points/confirm`, body)
}

export function submitDelivery(id: string, body: PkDeliveryDTO) {
  return request.post<ApiResponse<PkMatterVO>>(`/api/pk/matter/${id}/delivery`, body)
}

export function bindSession(id: string, sessionId: string) {
  return request.post<ApiResponse<PkMatterVO>>(`/api/pk/matter/${id}/bind-session`, { sessionId })
}

export function importSession(id: string, sessionId: string) {
  return request.post<ApiResponse<PkMatterVO>>(`/api/pk/matter/${id}/import-session`, { sessionId })
}

export function listCandidateSessions(id: string) {
  return request.get<ApiResponse<ChatSessionVO[]>>(`/api/pk/matter/${id}/candidate-sessions`)
}

export function exportDocx(id: string, body?: {
  markdown?: string
  title?: string
  diagramMode?: string
  exportId?: string
}) {
  return request.post<ApiResponse<PkArtifactVO>>(`/api/pk/matter/${id}/export-docx`, body || {})
}

export function createExportSession(id: string, body?: {
  markdown?: string
  diagramMode?: string
}) {
  return request.post<ApiResponse<{
    sessionId: string
    diagramMode: string
    figures: Array<{ index: number; preview: string }>
    message?: string
  }>>(`/api/pk/matter/${id}/export-session`, body || {})
}

export function renderExportFigure(
  id: string,
  sessionId: string,
  index: number,
  body?: { diagramMode?: string; force?: boolean },
) {
  return request.post<ApiResponse<{
    ok: boolean
    index: number
    status: string
    engine: string
    message?: string
    preview?: string
  }>>(`/api/pk/matter/${id}/export-session/${sessionId}/figure/${index}`, body || {})
}

export function finalizeExportSession(
  id: string,
  sessionId: string,
  body?: { title?: string; diagramMode?: string },
) {
  return request.post<ApiResponse<PkArtifactVO>>(
    `/api/pk/matter/${id}/export-session/${sessionId}/finalize`,
    body || {},
  )
}

export function downloadArtifact(matterId: string, artifactId: string) {
  return request.get(`/api/pk/matter/${matterId}/artifacts/${artifactId}/download`, {
    responseType: 'blob',
  })
}

export function bootstrapStatus() {
  return request.get<ApiResponse<{
    skills: { id: string; name: string }[]
    agents: Record<string, string | null>
    hasModel: boolean
    hasStorage?: boolean
  }>>('/api/pk/bootstrap/status')
}

export function ensureAgents() {
  return request.post<ApiResponse<Record<string, string>>>('/api/pk/bootstrap/agents')
}
