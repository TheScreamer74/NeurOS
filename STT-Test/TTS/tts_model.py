import asyncio
import websockets
import json
import base64
import torch
import numpy as np
from classes import Bicodec, Spark

device = "mps" if torch.backends.mps.is_available() else "cpu"
AUDIO_PROMPT_PATH = "sw3.wav"

bicodec = Bicodec("BiCodec", "wav2vec2-ST")
spark = Spark("model.q8_0.gguf")


async def handle_ws():
    global bicodec
    global spark
    
    uri = "ws://192.168.101.112:8080/models/connect"

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

                    tokens, codes = bicodec.encode(AUDIO_PROMPT_PATH)

                    codes = spark.generate(text, codes)
                
                    audio_array = bicodec.decode(tokens, codes)
                    audio_bytes = audio_array.astype(np.float32).tobytes()
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
