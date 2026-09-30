"""PatentKing tools sidecar — FastAPI app.

LLM API keys are NOT configured here; users configure models in Apboa Console UI.
"""

from __future__ import annotations

from typing import Literal, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .export_docx import export_markdown_to_docx, get_export_file
from .export_session import create_export_session, finalize_export_session, render_session_figure
from .search import search_prior_art

app = FastAPI(
    title="PatentKing patent-tools",
    version="0.2.0",
    description="Sidecar for CNIPA search, office convert, mermaid/docx export.",
)


class HealthResponse(BaseModel):
    status: str
    service: str


@app.get("/healthz", response_model=HealthResponse)
def healthz() -> HealthResponse:
    return HealthResponse(status="ok", service="patent-tools")


class EchoRequest(BaseModel):
    message: str


@app.post("/v1/echo")
def echo(body: EchoRequest) -> dict[str, str]:
    return {"echo": body.message}


class PriorArtSearchRequest(BaseModel):
    query: str = Field(..., description="检索关键词或一个技术语义块")
    limit: int = Field(8, ge=1, le=20)
    patent_type: Optional[str] = Field(
        "invention",
        description="invention | utility_model | design | all，默认 invention（发明公布+授权）",
    )


@app.post("/v1/cnipa/search")
def cnipa_search(body: PriorArtSearchRequest) -> dict:
    return search_prior_art(body.query, body.limit, patent_type=body.patent_type)


class TokenlabConfig(BaseModel):
    api_key: str = Field(..., description="Tokenlab API Key")
    base_url: str = Field("https://api.tokenlab.cc.cd/v1", description="主 Base URL")
    fallback_base_url: Optional[str] = Field(None, description="备用 Base URL")
    model: str = Field("gpt-image-2", description="模型")
    size: str = Field("1024x1024", description="尺寸")
    quality: str = Field("high", description="质量")
    network: str = Field("direct", description="direct | env | http-proxy | socks5-proxy")
    proxy: Optional[str] = Field(None, description="代理地址")


class MdToDocxRequest(BaseModel):
    markdown: str = Field(..., description="交底 Markdown 正文，可含 mermaid 图")
    title: str = Field("disclosure", description="输出文件名前缀")
    diagram_mode: Literal["png", "auto"] = Field(
        "png",
        description="png=本机 mmdc 机器渲染；auto=Tokenlab 自动生成（需 tokenlab 配置）",
    )
    include_pdf: bool = Field(False, description="是否在 Word 后再转 PDF")
    return_base64: bool = Field(False, description="是否在响应中附带 docx_base64")
    tokenlab: Optional[TokenlabConfig] = Field(None, description="auto 模式下的 Tokenlab 配置")


@app.post("/v1/export/md-to-docx")
def export_md_to_docx(body: MdToDocxRequest) -> dict:
    """机器渲染 mermaid → PNG，并生成嵌入图片的 Word。"""
    tokenlab_payload = None
    if body.tokenlab is not None:
        tokenlab_payload = body.tokenlab.model_dump()
    return export_markdown_to_docx(
        body.markdown,
        title=body.title,
        diagram_mode=body.diagram_mode,
        include_pdf=body.include_pdf,
        return_base64=body.return_base64,
        tokenlab=tokenlab_payload,
    )


class ExportSessionCreateRequest(BaseModel):
    markdown: str = Field(..., description="交底 Markdown")
    diagram_mode: str = Field("png", description="png | auto")


@app.post("/v1/export/session")
def export_session_create(body: ExportSessionCreateRequest) -> dict:
    return create_export_session(body.markdown, diagram_mode=body.diagram_mode)


class ExportSessionFigureRequest(BaseModel):
    diagram_mode: Optional[str] = Field(None, description="覆盖会话默认模式")
    force: bool = Field(False, description="强制重渲已成功的图")
    tokenlab: Optional[TokenlabConfig] = Field(None, description="auto 模式 Tokenlab 配置")


@app.post("/v1/export/session/{session_id}/figure/{index}")
def export_session_render_figure(
    session_id: str,
    index: int,
    body: ExportSessionFigureRequest,
) -> dict:
    tl = body.tokenlab.model_dump() if body.tokenlab else None
    return render_session_figure(
        session_id,
        index,
        diagram_mode=body.diagram_mode,
        tokenlab=tl,
        force=body.force,
    )


class ExportSessionFinalizeRequest(BaseModel):
    title: str = Field("disclosure", description="输出文件名前缀")
    diagram_mode: Optional[str] = Field(None, description="png | auto")


@app.post("/v1/export/session/{session_id}/finalize")
def export_session_finalize(session_id: str, body: ExportSessionFinalizeRequest) -> dict:
    return finalize_export_session(
        session_id,
        title=body.title,
        diagram_mode=body.diagram_mode,
    )


class MermaidRenderRequest(BaseModel):
    markdown: str = Field(..., description="含 mermaid 围栏的 Markdown")
    title: str = Field("disclosure", description="输出文件名前缀")


@app.post("/v1/render/mermaid")
def render_mermaid(body: MermaidRenderRequest) -> dict:
    return export_markdown_to_docx(
        body.markdown,
        title=body.title,
        diagram_mode="png",
        include_pdf=False,
        return_base64=False,
    )


@app.get("/v1/export/files/{export_id}")
def download_export(export_id: str):
    path = get_export_file(export_id)
    if path is None:
        raise HTTPException(status_code=404, detail="export not found")
    meta = path.with_suffix(".meta")
    filename = f"{export_id}.docx"
    if meta.is_file():
        for line in meta.read_text(encoding="utf-8").splitlines():
            if line.startswith("filename="):
                filename = line.split("=", 1)[1].strip() or filename
                break
    return FileResponse(
        path,
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        filename=filename,
    )
