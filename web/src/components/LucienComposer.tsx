import 'regenerator-runtime/runtime';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AudioLines, Loader2, Plus, SendHorizontal, Square } from 'lucide-react';
import { stopSpeaking } from '../utils/speech';
import { type VoiceLang } from '../api/lucienApi';
import SpeechRecognition, { useSpeechRecognition } from 'react-speech-recognition';

const MAX_CHARS = 2000;
const COUNTER_AT = 1600; // only surface the counter once it starts to matter

const VOICE_LANG_OPTIONS: { value: VoiceLang; label: string }[] = [
  { value: 'en', label: 'EN' },
  { value: 'hi', label: 'हिं' },
  { value: 'ta', label: 'தமி' },
  { value: 'kn', label: 'ಕನ್ನ' },
  { value: 'te', label: 'తెలు' },
];

const LANG_MAP: Record<VoiceLang, string> = {
  en: 'en-US',
  hi: 'hi-IN',
  ta: 'ta-IN',
  kn: 'kn-IN',
  te: 'te-IN',
};

interface Props {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  onStop: () => void;
  /** Reply in flight — the send control becomes Stop. */
  busy: boolean;
  disabled: boolean;
  placeholder: string;
  /** Raised when a control has no backend yet, so the panel can explain itself. */
  onUnavailable: (what: string) => void;
  /** Language used both for spoken replies and to hint the transcription backend. */
  voiceLang: VoiceLang;
  onVoiceLangChange: (lang: VoiceLang) => void;
}

export const LucienComposer = React.forwardRef<HTMLTextAreaElement, Props>(function LucienComposer(
  { value, onChange, onSend, onStop, busy, disabled, placeholder, onUnavailable, voiceLang, onVoiceLangChange }, ref,
) {
  const innerRef = useRef<HTMLTextAreaElement | null>(null);
  const [focused, setFocused] = useState(false);
  const [micState, setMicState] = useState<'idle' | 'recording' | 'transcribing'>('idle');
  const startValueRef = useRef('');
  const valueRef = useRef(value);
  valueRef.current = value;
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const {
    transcript,
    resetTranscript,
    browserSupportsSpeechRecognition,
  } = useSpeechRecognition();

  // Clean up on unmount
  useEffect(() => {
    return () => {
      SpeechRecognition.abortListening();
    };
  }, []);

  // Update textbox value live as transcript changes
  useEffect(() => {
    if (micState === 'recording' && transcript) {
      const base = startValueRef.current ? startValueRef.current + ' ' : '';
      onChangeRef.current((base + transcript).slice(0, MAX_CHARS));
    }
  }, [transcript, micState]);

  const cancelRecording = useCallback(() => {
    SpeechRecognition.abortListening();
    setMicState('idle');
  }, []);

  const toggleMic = useCallback(async () => {
    console.warn('[lucien-mic] toggleMic clicked, micState =', micState);

    if (micState === 'transcribing') {
      return;
    }

    if (micState === 'recording') {
      SpeechRecognition.stopListening();
      setMicState('idle');
      return;
    }

    if (!browserSupportsSpeechRecognition) {
      console.warn('[lucien-mic] SpeechRecognition not supported in this browser');
      onUnavailable('Voice input (SpeechRecognition not supported in this browser)');
      return;
    }

    stopSpeaking(); // don't let Lucien talk over the user
    try {
      console.warn('[lucien-mic] starting speech recognition…');
      startValueRef.current = valueRef.current;
      resetTranscript();
      setMicState('recording');
      await SpeechRecognition.startListening({
        continuous: true,
        language: LANG_MAP[voiceLang] || 'en-US',
      });
    } catch (e) {
      console.error('[lucien-mic] SpeechRecognition failed to start:', e);
      onUnavailable('Could not start voice input.');
      setMicState('idle');
    }
  }, [micState, onUnavailable, voiceLang, browserSupportsSpeechRecognition, resetTranscript]);

  const setRefs = useCallback((el: HTMLTextAreaElement | null) => {
    innerRef.current = el;
    if (typeof ref === 'function') ref(el);
    else if (ref) (ref as React.MutableRefObject<HTMLTextAreaElement | null>).current = el;
  }, [ref]);

  // Grow with content up to a ceiling, then scroll inside the field.
  useEffect(() => {
    const el = innerRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight + 2, 168)}px`;
  }, [value]);

  const canSend = value.trim().length > 0 && !disabled && !busy;
  const remaining = MAX_CHARS - value.length;

  const handleSend = useCallback(() => {
    if (micState !== 'idle') cancelRecording();
    onSend();
  }, [micState, cancelRecording, onSend]);

  const listening = micState !== 'idle';

  return (
    <div className="lucien-composer-wrap">
      {listening && (
        <div className="lucien-listening-badge" role="status">
          <span className="lucien-listening-dot" aria-hidden="true" />
          {micState === 'recording' ? 'Listening…' : 'Transcribing…'}
        </div>
      )}
      <div className={`lucien-composer${focused ? ' is-focused' : ''}${disabled ? ' is-disabled' : ''}`}>
        <button type="button" className="lucien-composer-tool"
          onClick={() => onUnavailable('Attachments')}
          aria-label="Attach a file" title="Attach a file">
          <Plus size={17} aria-hidden="true" />
        </button>

        <textarea
          ref={setRefs}
          className="lucien-composer-area"
          value={value}
          rows={1}
          placeholder={placeholder}
          disabled={disabled}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          onChange={e => onChange(e.target.value.slice(0, MAX_CHARS))}
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              if (canSend) handleSend();
            }
          }}
        />

        <div className="lucien-composer-right">
          {remaining <= MAX_CHARS - COUNTER_AT && (
            <span className={`lucien-composer-count${remaining <= 0 ? ' is-max' : ''}`}>{remaining}</span>
          )}
          <select
            className="lucien-voice-lang-select"
            value={voiceLang}
            onChange={e => onVoiceLangChange(e.target.value as VoiceLang)}
            aria-label="Voice language (spoken replies and dictation)"
            title="Voice language"
          >
            {VOICE_LANG_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
          <button type="button" className={`lucien-composer-tool${micState === 'recording' ? ' is-listening' : ''}`}
            onClick={toggleMic}
            disabled={micState === 'transcribing'}
            aria-label={micState === 'recording' ? 'Stop dictation' : 'Dictate a message'}
            aria-pressed={micState === 'recording'}
            title={micState === 'recording' ? 'Stop dictation' : 'Dictate a message'}>
            {micState === 'transcribing'
              ? <Loader2 size={16} className="lucien-spin" aria-hidden="true" />
              : <AudioLines size={16} aria-hidden="true" />}
          </button>
          {busy ? (
            <button type="button" className="lucien-composer-send is-stop" onClick={onStop}
              aria-label="Stop generating" title="Stop generating">
              <Square size={12} fill="currentColor" aria-hidden="true" />
            </button>
          ) : (
            <button type="button" className="lucien-composer-send" onClick={handleSend} disabled={!canSend}
              aria-label="Send message" title="Send">
              <SendHorizontal size={16} aria-hidden="true" />
            </button>
          )}
        </div>
      </div>

    </div>
  );
});
