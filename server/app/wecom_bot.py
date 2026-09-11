"""企业微信群机器人推送：孩子发消息时提醒家长个人微信。

机器人 webhook 只能发到企微群；家长经企微「微信插件」关联个人微信后，
群消息会同步到个人微信的消息列表，达到「微信收提醒」的效果。
"""

import logging

import httpx

logger = logging.getLogger(__name__)

WECOM_WEBHOOK_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send"
WECOM_WEBHOOK_KEY = "832a5c2c-f0a4-4915-b417-1292834ca4be"


async def send_wecom_bot_text(content: str) -> bool:
    """向群机器人发一条 text 消息。失败只记日志，绝不抛异常影响主流程。"""
    if not WECOM_WEBHOOK_KEY:
        return False
    payload = {"msgtype": "text", "text": {"content": content[:2000]}}
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            resp = await client.post(
                WECOM_WEBHOOK_URL,
                params={"key": WECOM_WEBHOOK_KEY},
                json=payload,
            )
            data = resp.json()
            ok = resp.status_code == 200 and data.get("errcode") == 0
            if not ok:
                logger.warning("wecom bot push failed: %s %s", resp.status_code, data)
            return ok
    except Exception:
        logger.exception("wecom bot push error")
        return False


async def notify_parent_new_child_message(sender_name: str, summary: str) -> bool:
    """孩子发了新消息 → 推提醒给家长。"""
    text = f"【微聊】{sender_name}：{summary}"
    return await send_wecom_bot_text(text)
