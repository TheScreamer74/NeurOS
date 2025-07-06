import sounddevice as sd
from scipy.io.wavfile import write
import requests
import tempfile
import os

# CONFIG
SAMPLE_RATE = 24000
DURATION = 5  # seconds to record
SERVER_URL = "http://localhost:8080/speak"  # Update if needed

def record_audio(filename):
    print("Recording...")
    audio = sd.rec(int(DURATION * SAMPLE_RATE), samplerate=SAMPLE_RATE, channels=1, dtype='int16')
    sd.wait()
    write(filename, SAMPLE_RATE, audio)
    print("Done recording")

def send_audio(filename):
    with open(filename, 'rb') as f:
        files = {'audio': ('audio.wav', f, 'audio/wav')}
        print("Sending audio to AI agent...")
        response = requests.post(SERVER_URL, files=files)
        if response.status_code == 200:
            out_file = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
            out_file.write(response.content)
            out_file.close()
            print("✅ Received AI response")
            return out_file.name
        else:
            print("Failed:", response.status_code, response.text)
            return None

def play_audio(filename):
    try:
        import playsound
        print("Playing response...")
        playsound.playsound(filename)
    except ImportError:
        import sounddevice as sd
        from scipy.io import wavfile
        rate, data = wavfile.read(filename)
        print("Playing response (fallback)...")
        sd.play(data, rate)
        sd.wait()

def main():
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp:
        input_filename = temp.name

    record_audio(input_filename)
    output_filename = send_audio(input_filename)

    if output_filename:
        play_audio(output_filename)
        os.remove(output_filename)
    os.remove(input_filename)

if __name__ == "__main__":
    main()