import torchaudio as ta
from chatterbox.tts import ChatterboxTTS

model = ChatterboxTTS.from_pretrained(device="cuda")

text = "Hi, this is me silverwolf I have a stupid japanese accent when I speak, it's fun don't you think ?"

AUDIO_PROMPT_PATH="sw3.wav"
wav = model.generate(text, audio_prompt_path=AUDIO_PROMPT_PATH)
ta.save("test-en.wav", wav, model.sr)