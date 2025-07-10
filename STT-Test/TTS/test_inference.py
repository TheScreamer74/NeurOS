import soundfile as sf

from classes import Bicodec, Spark
from utils import Timer

input = "sw3.wav"
output = "output.wav"
text = "My AI Grok is really cool now, I think juste like me he loves Hitler"

with Timer("Loaded bicodec"):
    bicodec = Bicodec("BiCodec", "wav2vec2-ST")

with Timer("Loaded spark"):
    spark = Spark("model.q4_k.gguf")

with Timer("Encoded audio"):
    tokens, codes = bicodec.encode(input)

with Timer("Generated audio"):
    codes = spark.generate(text, codes)

with Timer("Decoded audio"):
    audio = bicodec.decode(tokens, codes)

sf.write(output, audio, bicodec.sample_rate)
spark.unload()
