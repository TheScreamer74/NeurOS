import asyncio
import websockets
import json
import base64
import torch
import numpy as np
from chatterbox.tts import ChatterboxTTS

device = "mps" if torch.backends.mps.is_available() else "cpu"
model = ChatterboxTTS.from_pretrained(device=device)

AUDIO_PROMPT_PATH = "sw3.wav"

async def handle_ws():
    uri = "ws://127.0.0.1:8080/models/connect"

    async with websockets.connect(uri) as websocket:
        await websocket.send(json.dumps({
            "type": "register",
            "modelId": "tts-model"
        }))
        print("Connected and registered as tts-model")

        while True:
            try:
                message = await websocket.recv()
                data = json.loads(message)

                if data.get("type") == "request":
                    request_id = data["requestId"]
                    text = data["payload"]
                    print(f"🔊 TTS request received: {text}")

                    # Generate audio (float32 PCM)
                    wav_tensor = model.generate(text, audio_prompt_path=AUDIO_PROMPT_PATH)  # (1, N)
                    wav_np = wav_tensor.squeeze().cpu().numpy()  # (N,)

                    # Ensure float32 type, raw bytes
                    audio_bytes = wav_np.astype(np.float32).tobytes()

                    # Encode base64
                    audio_base64 = base64.b64encode(audio_bytes).decode("utf-8")

                    # Send raw 32-bit float PCM data
                    await websocket.send(json.dumps({
                        "type": "response",
                        "modelId": "tts-model",
                        "requestId": request_id,
                        "payload": audio_base64
                    }))
                    print(f"Sent response for requestId: {request_id}")

            except Exception as e:
                print(f"Error: {e}")
                break

asyncio.run(handle_ws())