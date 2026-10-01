"""
Generate a small, native-PCM WAV test asset for the OBD media root.

Why a hand-made WAV rather than an MP3:
  mod_av is BROKEN in this image (it cannot load, missing libavformat.so.62),
  so FreeSWITCH cannot decode compressed formats here. WAV with 16-bit PCM is
  handled by FreeSWITCH's own core file handling and needs no module at all.
  That matters because a media failure caused by a missing codec would be
  indistinguishable from a media failure caused by a missing file - and those
  are exactly the two things Phase C has to tell apart.

Format chosen to match what the telephony stack actually negotiates: 8 kHz,
mono, 16-bit signed PCM, which is exactly PCMU's sample rate and is therefore
transcoded trivially.

The file is written to the application's documented media root,
backend/data/audio, which is bind-mounted read-only into FreeSWITCH at
/media/obd. That directory is git-ignored for its contents, so this is a local
test asset and not a repository change.
"""
import math
import os
import struct
import wave

RATE = 8000
SECONDS = 3.0
# Two tones, so it is audible that playback progressed rather than just started.
SEGMENTS = [(440.0, 1.5), (660.0, 1.5)]

out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "..", "backend", "data", "audio")
out_dir = os.path.normpath(out_dir)
path = os.path.join(out_dir, "phase-c-test-tone.wav")

frames = bytearray()
for freq, secs in SEGMENTS:
    n = int(RATE * secs)
    for i in range(n):
        # 10 ms fade at each edge so the tone does not click.
        env = min(1.0, i / (RATE * 0.01), (n - i) / (RATE * 0.01))
        val = int(12000 * env * math.sin(2 * math.pi * freq * i / RATE))
        frames += struct.pack("<h", val)

os.makedirs(out_dir, exist_ok=True)
with wave.open(path, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(bytes(frames))

print("wrote %s" % path)
print("  %d bytes, %d Hz, mono, 16-bit PCM, %.1f s"
      % (os.path.getsize(path), RATE, SECONDS))
