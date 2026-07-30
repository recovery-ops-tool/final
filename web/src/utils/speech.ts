// Voice I/O for Lucien. Spoken replies use the browser's built-in
// speechSynthesis (or an HF-TTS clip from the backend) — no recording
// involved. Dictation records the mic locally with MediaRecorder and sends
// the clip to the backend (tts-service's Whisper /stt endpoint, proxied
// through LucienController) for transcription — the browser's own
// SpeechRecognition API was tried first but dropped: Chrome routes it
// through an undocumented, third-party-unsupported Google endpoint that
// reliably fails silently (no result, no error, indefinite "listening")
// for non-google.com origins.

function stripForSpeech(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^[-*+]\s+/gm, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

export function isSpeechSynthesisSupported(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window;
}

/** Speaks `text` aloud, replacing anything currently being spoken. */
export function speak(text: string): void {
  if (!isSpeechSynthesisSupported()) return;
  const clean = stripForSpeech(text);
  if (!clean) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(clean);
  utterance.rate = 1;
  utterance.pitch = 1;
  window.speechSynthesis.speak(utterance);
}

let currentClip: HTMLAudioElement | null = null;

/** Stops both browser speechSynthesis and any HF-TTS audio clip currently playing. */
export function stopSpeaking(): void {
  if (isSpeechSynthesisSupported()) window.speechSynthesis.cancel();
  if (currentClip) {
    currentClip.pause();
    currentClip.src = '';
    currentClip = null;
  }
}

/**
 * Plays a synthesized speech clip (e.g. from the tts-service backend),
 * replacing anything currently being spoken. The object URL is revoked once
 * playback ends or fails so the blob doesn't leak. Returns the play()
 * promise so callers can detect and react to playback failures (e.g.
 * browser autoplay-policy rejection) instead of it failing silently.
 */
export function playSpeechClip(audio: Blob): Promise<void> {
  stopSpeaking();
  const url = URL.createObjectURL(audio);
  const el = new Audio(url);
  currentClip = el;
  const cleanup = () => {
    URL.revokeObjectURL(url);
    if (currentClip === el) currentClip = null;
  };
  el.addEventListener('ended', cleanup);
  el.addEventListener('error', cleanup);
  return el.play().catch((e) => {
    cleanup();
    throw e;
  });
}

export function isMicRecordingSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
    && typeof MediaRecorder !== 'undefined';
}

export interface Recording {
  /** Stops the recorder and resolves with the captured clip. */
  stop: () => Promise<Blob>;
  /** Discards the recording without producing a clip (e.g. panel closed mid-record). */
  cancel: () => void;
}

/** Preferred codec for MediaRecorder output — the tts-service /stt endpoint decodes via PyAV, which handles this fine; falls back to the browser default if unsupported. */
const PREFERRED_MIME_TYPE = 'audio/webm;codecs=opus';

/**
 * Requests mic access and starts recording immediately. The caller calls
 * `stop()` when the user releases the mic button to get the clip back for
 * upload. Throws the `getUserMedia` rejection (e.g. a `DOMException` named
 * `NotAllowedError`/`NotFoundError`/`NotReadableError`) if access fails, or
 * if the browser lacks microphone/MediaRecorder support.
 */
export async function startRecording(): Promise<Recording> {
  if (!isMicRecordingSupported()) throw new Error('unsupported');

  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const mimeType = MediaRecorder.isTypeSupported(PREFERRED_MIME_TYPE) ? PREFERRED_MIME_TYPE : undefined;
  const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
  const chunks: BlobPart[] = [];
  recorder.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data); };
  const stopTracks = () => stream.getTracks().forEach(t => t.stop());

  recorder.start(250);

  return {
    stop: () => new Promise<Blob>((resolve) => {
      if (recorder.state === 'inactive') {
        resolve(new Blob(chunks, { type: recorder.mimeType || 'audio/webm' }));
        return;
      }
      recorder.onstop = () => {
        stopTracks();
        resolve(new Blob(chunks, { type: recorder.mimeType || 'audio/webm' }));
      };
      recorder.stop();
    }),
    cancel: () => {
      recorder.onstop = null;
      if (recorder.state !== 'inactive') recorder.stop();
      stopTracks();
    },
  };
}

/** Turns a `getUserMedia`/recording failure into what the agent should be told. */
export function micAccessErrorMessage(error: unknown): string {
  const name = error instanceof DOMException ? error.name
    : error instanceof Error && error.message === 'unsupported' ? 'unsupported'
    : '';
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return "Microphone access is blocked for this site. Check the mic icon in your browser's address bar (and your OS microphone privacy settings), then try again.";
    case 'NotFoundError':
      return 'No microphone found — check one is connected and not in use by another app.';
    case 'NotReadableError':
      return 'Could not access the microphone — it may be in use by another app.';
    case 'unsupported':
      return "Voice input isn't supported in this browser.";
    default:
      return 'Could not start voice input — try again.';
  }
}
