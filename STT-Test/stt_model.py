import json
import ssl

import websockets
import asyncio
import whisper
import tempfile
import base64

ssl._create_default_https_context = ssl._create_unverified_context

# Load the Whisper model once globally
model = whisper.load_model("base")  # or "small", "medium", "large", "tiny"

async def stt_model():
    uri = "ws://127.0.0.1:8080/models/connect"
    async with websockets.connect(uri, max_size=None) as websocket:
        # Register the model
        register_msg = {
            "type": "register",
            "modelId": "stt-model"
        }
        await websocket.send(json.dumps(register_msg))
        print("Registered as STT model")

        async for message in websocket:
            msg = json.loads(message)
            if msg.get("type") != "request":
                continue

            payload = msg.get("payload")
            request_id = msg.get("requestId")

            print(f"Received audio payload (base64) for request: {request_id}")

            try:
                # Decode base64 audio
                audio_bytes = base64.b64decode(payload)

                # Write to temp WAV file
                with tempfile.NamedTemporaryFile(suffix=".wav", delete=True) as f:
                    f.write(audio_bytes)
                    f.flush()

                    # Transcribe using Whisper
                    result = model.transcribe(f.name)
                    recognized_text = result["text"].strip()

                print(f"Transcribed: {recognized_text}")

                # Send back the transcription
                response_msg = {
                    "type": "response",
                    "modelId": "stt-model",
                    "payload": recognized_text,
                    "requestId": request_id
                }
                await websocket.send(json.dumps(response_msg))
                print(f"Sent recognized text for request: {request_id}")

            except Exception as e:
                print(f"Error during STT: {e}")
                await websocket.send(json.dumps({
                    "type": "response",
                    "modelId": "stt-model",
                    "payload": "[STT ERROR]",
                    "requestId": request_id
                }))

asyncio.run(stt_model())