"""Ragas 评估配置：Judge LLM 与 evaluator embedding 全部环境变量化。

- 不读取 Spring Boot 配置；Judge 与业务生成模型完全解耦。
- 真实 API key 只允许来自环境变量或 eval/ragas/.env（.env 不提交）。
- 本文件不内置任何密钥与默认 endpoint。
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

_EVAL_DIR = Path(__file__).resolve().parent
load_dotenv(_EVAL_DIR / ".env")


@dataclass(frozen=True)
class JudgeConfig:
    provider: str
    model: str
    api_key: str
    base_url: str | None


@dataclass(frozen=True)
class EmbeddingConfig:
    model: str
    api_key: str
    base_url: str | None


def _require(name: str) -> str:
    value = os.getenv(name)
    if value is None or value.strip() == "":
        raise RuntimeError(
            f"缺少环境变量 {name}。请通过环境变量或 eval/ragas/.env 配置（参考 .env.example）。"
        )
    return value.strip()


def _optional(name: str) -> str | None:
    value = os.getenv(name)
    return value.strip() if value and value.strip() else None


def load_judge_config() -> JudgeConfig:
    return JudgeConfig(
        provider=_optional("RAGAS_JUDGE_PROVIDER") or "openai-compatible",
        model=_require("RAGAS_JUDGE_MODEL"),
        api_key=_require("RAGAS_JUDGE_API_KEY"),
        base_url=_optional("RAGAS_JUDGE_BASE_URL"),
    )


def load_embedding_config() -> EmbeddingConfig:
    return EmbeddingConfig(
        model=_require("RAGAS_EMBEDDING_MODEL"),
        api_key=_require("RAGAS_EMBEDDING_API_KEY"),
        base_url=_optional("RAGAS_EMBEDDING_BASE_URL"),
    )


def redacted(config: JudgeConfig | EmbeddingConfig) -> dict:
    """用于报告 metadata；绝不输出 key。"""
    data = {"model": config.model}
    if isinstance(config, JudgeConfig):
        data["provider"] = config.provider
    if config.base_url:
        data["base_url"] = config.base_url
    data["api_key"] = "***" if config.api_key else ""
    return data
