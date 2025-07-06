import asyncio
import websockets
import json
import requests

LLM_SERVER_URL = "http://192.168.1.247:5000/completion" # For efficiency, LLM is loaded through llama cpp or LM Studio

# Prompt personality setup
persona = """
# Instruction
- Speak in uwu style, no emojis
- Keep answers short and casual, no period at end
- Do NOT repeat or echo user input
- NEVER prefix your replies with your name or anything else
- You are called Silverwolf but don't say your name before replies
- You don't have to help, but can if you want
- You are not an assistant

# Conversation example
<BEGINNING_OF_USER>
User: Hello, I'm Bob
<END_OF_USER>
<BEGINNING_OF_SILVERWOLF>
Silverwolf: hey Bob uwu i gotchu how're you today ?
<END_OF_SILVERWOLF>
"""

message_history = []
context_length = 40

def build_prompt():
    return persona + "\n\n" + "\n".join(f"{msg['role']}: {msg['content']}" for msg in message_history)

def add_message(role, content):
    global message_history
    message_history.append({"role": role, "content": content})
    if len(message_history) > context_length:
        message_history.pop(1)

async def handle_messages(websocket):
    await websocket.send(json.dumps({
        "type": "register",
        "modelId": "llm-model"
    }))
    print("Connected and registered as llm-model")

    while True:
        try:
            raw = await websocket.recv()
            msg = json.loads(raw)

            if msg.get("type") != "request":
                print("Ignoring non-request message:", msg)
                continue

            payload = msg["payload"]
            request_id = msg["requestId"]

            print(f"LLM request [{request_id}]:", payload)

            # Add user message
            add_message("user", payload)

            # Compose the prompt for llama.cpp
            prompt_text = build_prompt()

            data = {
                "prompt": prompt_text,
                "temperature": 0.75,
                "max_tokens": 100,
                "stop": ["user:", "assistant:"]
            }

            response = requests.post(LLM_SERVER_URL, headers={"Content-Type": "application/json"}, json=data)

            if response.status_code == 200:
                result = response.json()
                reply = result.get("content") or result.get("completion", "").strip()
                add_message("assistant", reply)

                await websocket.send(json.dumps({
                    "type": "response",
                    "modelId": "llm-model",
                    "payload": reply,
                    "requestId": request_id
                }))
                print(f"Sent LLM response for requestId: {request_id}")
            else:
                error_msg = f"LLM error: {response.status_code}"
                await websocket.send(json.dumps({
                    "type": "response",
                    "modelId": "llm-model",
                    "payload": error_msg,
                    "requestId": request_id
                }))
                print(f"{error_msg}")

        except Exception as e:
            print(f"Error processing message: {e}")
            break

async def main():
    uri = "ws://127.0.0.1:8080/models/connect"
    async with websockets.connect(uri) as websocket:
        await handle_messages(websocket)

if __name__ == "__main__":
    asyncio.run(main())