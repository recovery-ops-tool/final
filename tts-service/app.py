"""
Local voice microservice — same pattern as Ollama serving the LLM: a small
always-on local process the Java backend calls over HTTP, so
LucienAgentLoop/LlamaClient don't need to embed Python.

TTS (/tts) wraps AI4Bharat's IndicF5 model. Supports exactly the 4 languages
Lucien needs to speak: Hindi, Tamil, Kannada, Telugu. Each is driven by a
reference voice clip + its transcript (F5-TTS style voice cloning) —
IndicF5's own official demo cross-lingually pairs a Punjabi reference with
Hindi text and a Tamil reference with Telugu/Malayalam text, since no
dedicated Hindi/Telugu reference clip ships with the model; we reuse those
exact pairings here.

STT (/stt) wraps faster-whisper for dictation — transcribing a short voice
clip is light enough to stay interactive on CPU, unlike IndicF5 TTS.

Run: .venv/Scripts/python.exe -m uvicorn app:app --host 127.0.0.1 --port 8100
"""
import io
import os
import tempfile
from pathlib import Path

import numpy as np
from faster_whisper import WhisperModel
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

# The IndicF5 TTS stack (torch/torchaudio/transformers + the gated
# ai4bharat/IndicF5 model) is a much heavier, more fragile install than STT
# needs (large native deps, a HF_TOKEN for a gated model) and is already
# unused in production (see lucien.tts.base-url comment in application.properties).
# Import it defensively so a missing/broken TTS environment degrades /tts to a
# clear 503 instead of taking the whole process — and /stt — down with it.
try:
    import soundfile as sf
    import torch
    import torchaudio
    from transformers import AutoModel

    def _patch_torchaudio_load() -> None:
        """Use soundfile for reference-audio loading.

        torchaudio 2.11+ defaults to torchcodec, which needs FFmpeg DLLs on Windows.
        IndicF5 only needs plain WAV loading, so soundfile is enough and avoids that
        native dependency chain.
        """

        def _load_with_soundfile(uri, *args, **kwargs):
            del args, kwargs
            data, sample_rate = sf.read(uri, always_2d=True)
            tensor = torch.from_numpy(data.T).float()
            return tensor, sample_rate

        torchaudio.load = _load_with_soundfile

    _patch_torchaudio_load()
    TTS_IMPORT_ERROR = None
except ImportError as _err:
    sf = torch = torchaudio = AutoModel = None
    TTS_IMPORT_ERROR = str(_err)

BASE_DIR = Path(__file__).parent
PROMPTS_DIR = BASE_DIR / "prompts"
SAMPLE_RATE = 24000

LANG_PROMPTS = {
    "hi": {
        "ref_audio": PROMPTS_DIR / "PAN_F_HAPPY_00002.wav",
        "ref_text": "ਇੱਕ ਗ੍ਰਾਹਕ ਨੇ ਸਾਡੀ ਬੇਮਿਸਾਲ ਸੇਵਾ ਬਾਰੇ ਦਿਲੋਂਗਵਾਹੀ ਦਿੱਤੀ ਜਿਸ ਨਾਲ ਸਾਨੂੰ ਅਨੰਦ ਮਹਿਸੂਸ ਹੋਇਆ।",
    },
    "ta": {
        "ref_audio": PROMPTS_DIR / "TAM_F_HAPPY_00001.wav",
        "ref_text": "நான் நெனச்ச மாதிரியே அமேசான்ல பெரிய தள்ளுபடி வந்திருக்கு. கம்மி காசுக்கே அந்தப் புது சேம்சங் மாடல வாங்கிடலாம்.",
    },
    "kn": {
        "ref_audio": PROMPTS_DIR / "KAN_F_HAPPY_00001.wav",
        "ref_text": "ನಮ್‌ ಫ್ರಿಜ್ಜಲ್ಲಿ  ಕೂಲಿಂಗ್‌ ಸಮಸ್ಯೆ ಆಗಿ ನಾನ್‌ ಭಾಳ ದಿನದಿಂದ ಒದ್ದಾಡ್ತಿದ್ದೆ, ಆದ್ರೆ ಅದ್ನೀಗ ಮೆಕಾನಿಕ್ ಆಗಿರೋ ನಿಮ್‌ ಸಹಾಯ್ದಿಂದ ಬಗೆಹರಿಸ್ಕೋಬೋದು ಅಂತಾಗಿ ನಿರಾಳ ಆಯ್ತು ನಂಗೆ.",
    },
    "te": {
        # No dedicated Telugu reference ships with the model — cross-lingual
        # synthesis with the Tamil reference clip works well in IndicF5 demos.
        "ref_audio": PROMPTS_DIR / "TAM_F_HAPPY_00001.wav",
        "ref_text": "நான் நெனச்ச மாதிரியே அமேசான்ல பெரிய தள்ளுபடி வந்திருக்கு. கம்மி காசுக்கே அந்தப் புது சேம்சங் மாடல வாங்கிடலாம்.",
    },
}

model = None


def get_model():
    global model
    if TTS_IMPORT_ERROR is not None:
        raise RuntimeError(f"TTS dependencies not installed: {TTS_IMPORT_ERROR}")
    if model is None:
        token = os.environ.get("HF_TOKEN")
        model = AutoModel.from_pretrained("ai4bharat/IndicF5", trust_remote_code=True, token=token)
        # The model's built-in post-processing (pydub silence-stripping + loudness
        # normalization) misfires in this environment and can wipe out perfectly
        # good audio entirely (verified: raw output was 11s of real speech, but
        # with remove_sil=True the response came back as an empty 44-byte file).
        # We do our own lightweight peak normalization instead — see normalize().
        model.config.remove_sil = False
    return model


def normalize(audio: np.ndarray, target_peak: float = 0.9) -> np.ndarray:
    peak = np.max(np.abs(audio))
    if peak > 0:
        audio = audio * (target_peak / peak)
    return audio


# faster-whisper's language codes match ours for the languages Lucien supports.
STT_LANGS = {"en", "hi", "ta", "kn", "te"}
WHISPER_MODEL_SIZE = os.environ.get("WHISPER_MODEL_SIZE", "small")

stt_model: WhisperModel | None = None


def get_stt_model() -> WhisperModel:
    global stt_model
    if stt_model is None:
        # int8 on CPU: smallest memory/latency footprint that still transcribes
        # a short dictation clip (a few seconds to under a minute) in a few
        # seconds — TTS-scale latency isn't viable for an interactive mic button.
        stt_model = WhisperModel(WHISPER_MODEL_SIZE, device="cpu", compute_type="int8")
    return stt_model


app = FastAPI(title="Lucien Voice Service")


@app.on_event("startup")
def preload_models():
    if TTS_IMPORT_ERROR is None:
        get_model()
    get_stt_model()


class SpeakRequest(BaseModel):
    text: str
    lang: str


@app.get("/health")
def health():
    return {
        "status": "ok",
        "tts_available": TTS_IMPORT_ERROR is None,
        "tts_languages": list(LANG_PROMPTS.keys()),
        "tts_model_loaded": model is not None,
        "stt_languages": sorted(STT_LANGS),
        "stt_model_loaded": stt_model is not None,
    }


@app.post("/tts")
def synthesize(req: SpeakRequest):
    if TTS_IMPORT_ERROR is not None:
        raise HTTPException(503, f"TTS is unavailable in this deployment: {TTS_IMPORT_ERROR}")

    lang = req.lang.lower()
    if lang not in LANG_PROMPTS:
        raise HTTPException(400, f"Unsupported language '{req.lang}'. Supported: {list(LANG_PROMPTS.keys())}")
    if not req.text.strip():
        raise HTTPException(400, "text must not be empty")

    prompt = LANG_PROMPTS[lang]
    m = get_model()
    audio = m(str(req.text), ref_audio_path=str(prompt["ref_audio"]), ref_text=prompt["ref_text"])

    if audio.dtype == np.int16:
        audio = audio.astype(np.float32) / 32768.0
    audio = normalize(np.array(audio, dtype=np.float32))

    buf = io.BytesIO()
    sf.write(buf, np.array(audio, dtype=np.float32), samplerate=SAMPLE_RATE, format="WAV")
    return Response(content=buf.getvalue(), media_type="audio/wav")


@app.post("/stt")
async def transcribe(audio: UploadFile = File(...), lang: str = Form("en")):
    lang = lang.lower()
    if lang not in STT_LANGS:
        raise HTTPException(400, f"Unsupported language '{lang}'. Supported: {sorted(STT_LANGS)}")

    data = await audio.read()
    if not data:
        raise HTTPException(400, "audio must not be empty")

    # faster-whisper reads from a path (via PyAV, which decodes webm/opus —
    # MediaRecorder's default output — without needing a system ffmpeg binary).
    # delete=False + a manual close before PyAV opens the path: on Windows a
    # second handle can't reliably read a NamedTemporaryFile while the first
    # (delete=True) handle is still open.
    suffix = Path(audio.filename or "clip.webm").suffix or ".webm"
    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    try:
        tmp.write(data)
        tmp.close()
        # Save a copy for debugging
        try:
            with open(r"C:\Users\Keerthana\.gemini\antigravity-cli\brain\79d819ae-e1dd-4039-8fea-75bff29ccf36\scratch\debug.webm", "wb") as f:
                f.write(data)
            print("[STT] Saved debug.webm successfully")
        except Exception as ex:
            print(f"[STT] Failed to save debug.webm: {ex}")
        # Decode and resample to 16kHz mono float32 using PyAV
        import av
        container = av.open(tmp.name)
        audio_stream = next(s for s in container.streams if s.type == 'audio')
        resampler = av.AudioResampler(format='flt', layout='mono', rate=16000)
        
        samples = []
        for frame in container.decode(audio_stream):
            resampled = resampler.resample(frame)
            for r_frame in resampled:
                # layout='mono' means shape is (1, samples)
                samples.append(r_frame.to_ndarray()[0])
        container.close()
        
        if samples:
            audio_arr = np.concatenate(samples)
            # Normalize to boost extremely quiet/faint speech
            audio_arr = normalize(audio_arr, target_peak=0.9)
        else:
            audio_arr = np.array([], dtype=np.float32)

        print(f"[STT] Transcribing normalized audio array of shape {audio_arr.shape}, language={lang}")
        segments, _info = get_stt_model().transcribe(
            audio_arr,
            language=lang,
            vad_filter=True,
            vad_parameters=dict(threshold=0.25)
        )
        segments = list(segments)
        print(f"[STT] Segments detected: {[s.text for s in segments]}")
        text = "".join(segment.text for segment in segments).strip()
    finally:
        os.unlink(tmp.name)

    return {"text": text, "language": lang}
