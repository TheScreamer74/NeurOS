import asyncio
import websockets
import json
import base64
import torch
import numpy as np
from chatterbox.tts import ChatterboxTTS

device = "mps" if torch.backends.mps.is_available() else "cpu"
AUDIO_PROMPT_PATH = "sw3.wav"

bicodec = Bicodec("BiCodec", "wav2vec2-ST")
spark = Spark("model.q4_k.gguf")
tokens, codes = bicodec.encode(input)

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

                    codes = spark.generate(text, codes)
                    audio_bytes = bicodec.decode(tokens, codes)

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
