"""设备模拟器 —— 模拟 Android 平板连接服务器，接收安装任务并上报状态。

用法（在 server/ 目录下）:
    python device_simulator.py

交互命令:
    s <task_id> <status>   → 上报任务状态 (downloading/installing/success/failed)
    q                      → 退出
"""

import asyncio
import json
import websockets

SERVER_WS = "ws://localhost:8000/ws/device"
DEVICE_ID = "test-device-01"
PSK = "device-preshared-key-2024"


async def listen_loop(ws):
    """后台任务：监听服务器推送的消息。"""
    while True:
        try:
            raw = await ws.recv()
            msg = json.loads(raw)
            msg_type = msg.get("type", "")

            if msg_type == "new_task":
                print(f"\n📩 收到安装任务！")
                print(f"   task_id: {msg.get('task_id')}")
                print(f"   apk_id:  {msg.get('apk_id')}")
                print(f"   apk:     {msg.get('apk_name')}")
                print(f"   下载URL: {msg.get('apk_download_url')}")
                print(f"\n   模拟上报: s {msg.get('task_id')} downloading")
            else:
                print(f"\n📩 收到消息: {json.dumps(msg, ensure_ascii=False, indent=2)}")
        except websockets.ConnectionClosed:
            print("\n❌ 连接已断开")
            break
        except Exception as e:
            print(f"\n⚠️ 接收异常: {e}")
            break


async def input_loop(ws):
    """前台交互：用户输入命令。"""
    print("=" * 50)
    print("  设备模拟器已连接 (test-device-01)")
    print("  命令:")
    print("    s <task_id> <status>  → 上报状态")
    print("    q                     → 退出")
    print("=" * 50)

    while True:
        try:
            cmd = await asyncio.get_event_loop().run_in_executor(None, input, "\n> ")
            cmd = cmd.strip()

            if cmd == "q":
                print("👋 断开连接...")
                await ws.close()
                break

            if cmd.startswith("s "):
                parts = cmd.split()
                if len(parts) >= 3:
                    task_id = int(parts[1])
                    status = parts[2]
                    payload = json.dumps({
                        "type": "task_status",
                        "task_id": task_id,
                        "status": status,
                        "error": ""
                    })
                    await ws.send(payload)
                    print(f"   ✅ 已上报: task={task_id} → {status}")
                else:
                    print("   用法: s <task_id> <status>")
            elif cmd:
                print(f"   未知命令: {cmd}")

        except EOFError:
            break


async def main():
    url = f"{SERVER_WS}?device_id={DEVICE_ID}&psk={PSK}"
    print(f"🔌 连接 {url} ...")

    async with websockets.connect(url) as ws:
        # 并行运行监听和交互
        await asyncio.gather(
            listen_loop(ws),
            input_loop(ws),
        )


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n👋 已退出")
