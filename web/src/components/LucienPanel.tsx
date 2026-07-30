import { useState, useEffect, useRef, useCallback } from 'react';
import {
  AlertCircle, ArrowDown, Check, History, Loader2, Plus, RefreshCw,
  ShieldAlert, X,
} from 'lucide-react';
import { useAuth } from '../AuthContext';
import { lucienApi, type VoiceLang } from '../api/lucienApi';
import { extractApiError } from '../utils/extractApiError';
import { speak, stopSpeaking, playSpeechClip } from '../utils/speech';
import { Logo } from './Logo';
import { LucienMessage, type ChatMessage, type Feedback } from './LucienMessage';
import { LucienHistory } from './LucienHistory';
import { LucienComposer } from './LucienComposer';
import lucienLogo from '../assets/images/lucien-logo.png';
import './LucienPanel.css';

interface PendingConfirm {
  actionId: string;
  summary: string;
  toolName?: string;
}

interface LucienPanelProps {
  open: boolean;
  onClose: () => void;
}

const HISTORY_KEY = 'lucien-chat-history';
const VOICE_LANG_KEY = 'lucien-voice-lang';
const MAX_HISTORY = 60;
const SEND_DEBOUNCE_MS = 100;

// Two examples, not a menu — one asks for judgement, one asks for drafting,
// which is the span of what Lucien is for. More cards read as a feature list.
const SAMPLE_PROMPTS = [
  {
    title: 'Work an avoidant borrower',
    body: 'How should I approach a borrower who has stopped taking calls?',
  },
  {
    title: 'Draft a broken-PTP follow-up',
    body: 'Draft a follow-up for a borrower who missed a promised payment.',
  },
];

const FOLLOW_UPS = [
  'Give me a shorter version.',
  'What are the compliance limits here?',
  'Draft that as a message I can send.',
];

function initialsOf(user?: { firstName?: string; lastName?: string; email?: string } | null) {
  if (user?.firstName && user?.lastName) return (user.firstName[0] + user.lastName[0]).toUpperCase();
  if (user?.firstName) return user.firstName.slice(0, 2).toUpperCase();
  if (user?.email) return user.email[0].toUpperCase();
  return '?';
}

export default function LucienPanel({ open, onClose }: LucienPanelProps) {
  const { user } = useAuth();
  const agentId = user?.agentId || user?.id || '';
  const agentFirstName = user?.firstName || 'Agent';

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>(() => {
    try { return JSON.parse(localStorage.getItem(HISTORY_KEY) ?? '[]'); } catch { return []; }
  });
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirm | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [feedback, setFeedback] = useState<Record<string, Feedback>>({});
  const [toast, setToast] = useState<string | null>(null);
  const [atBottom, setAtBottom] = useState(true);
  const [loadingSession, setLoadingSession] = useState(false);
  const [voiceLang, setVoiceLang] = useState<VoiceLang>(() => {
    const stored = localStorage.getItem(VOICE_LANG_KEY);
    return (stored as VoiceLang) || 'en';
  });

  const panelRef = useRef<HTMLElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const lastSentRef = useRef(0);
  const startingRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    try { localStorage.setItem(HISTORY_KEY, JSON.stringify(messages.slice(-MAX_HISTORY))); } catch {/* quota */}
  }, [messages]);

  useEffect(() => {
    try { localStorage.setItem(VOICE_LANG_KEY, voiceLang); } catch {/* quota */}
  }, [voiceLang]);

  const handleVoiceLangChange = useCallback((lang: VoiceLang) => {
    setVoiceLang(lang);
  }, []);

  /**
   * Speaks a reply aloud. Uses the backend translation and synthesis pipeline
   * for Indic languages, falling back to the browser's built-in voice.
   */
  const speakReply = useCallback(async (text: string) => {
    if (voiceLang === 'en') {
      speak(text);
      return;
    }

    try {
      const blob = await lucienApi.speak(text, voiceLang);
      await playSpeechClip(blob);
    } catch (e) {
      console.warn('[lucien] backend TTS failed, falling back to browser voice:', e);
      speak(text);
    }
  }, [voiceLang]);

  // inert when closed so nothing inside is tabbable behind the app
  useEffect(() => {
    panelRef.current?.toggleAttribute('inert', !open);
    if (!open) { setError(null); setHistoryOpen(false); stopSpeaking(); }
  }, [open]);

  // Track whether the reader is pinned to the bottom; only autoscroll if so.
  const onListScroll = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    setAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 40);
  }, []);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'smooth') => {
    const el = listRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior });
  }, []);

  useEffect(() => {
    if (atBottom) scrollToBottom();
  }, [messages, sending, atBottom, scrollToBottom]);

  useEffect(() => {
    if (!open) return;
    const id = window.setTimeout(() => inputRef.current?.focus(), 260);
    return () => window.clearTimeout(id);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (historyOpen) { setHistoryOpen(false); return; }
      onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose, historyOpen]);

  useEffect(() => {
    if (!toast) return;
    // Longer messages (e.g. mic-permission instructions) get more time on screen to read.
    const id = window.setTimeout(() => setToast(null), Math.max(2600, toast.length * 60));
    return () => window.clearTimeout(id);
  }, [toast]);

  const startSession = useCallback(async () => {
    if (startingRef.current) return;
    if (!agentId) {
      setError('No agent profile linked — Lucien cannot start.');
      return;
    }
    startingRef.current = true;
    setStarting(true);
    setError(null);
    try {
      const session = await lucienApi.startSession({ agentId, agentFirstName });
      setSessionId(session.sessionId);
      setMessages([]);
      setPendingConfirm(null);
    } catch (e) {
      console.error('Lucien startSession failed', e);
      setError(extractApiError(e, 'Lucien is unavailable right now. Please try again.'));
    } finally {
      setStarting(false);
      startingRef.current = false;
    }
  }, [agentId, agentFirstName]);

  useEffect(() => {
    if (open && !sessionId && !startingRef.current && !error) startSession();
  }, [open, sessionId, error, startSession]);

  const pushReply = useCallback((resp: Awaited<ReturnType<typeof lucienApi.sendMessage>>) => {
    if (resp.blocked) {
      setMessages(prev => [...prev, {
        id: resp.messageId ?? `a-${Date.now()}`,
        role: 'ASSISTANT',
        content: '',
        blocked: true,
        blockReason: resp.blockReason,
        createdAt: resp.timestamp,
      }]);
      return;
    }
    setMessages(prev => [...prev, {
      id: resp.messageId ?? `a-${Date.now()}`,
      role: 'ASSISTANT',
      content: resp.reply || 'Lucien had nothing to add.',
      createdAt: resp.timestamp,
      modelName: resp.modelName,
    }]);
    if (resp.reply) speakReply(resp.reply);
    if (resp.confirmationRequired && resp.pendingActionId) {
      setPendingConfirm({
        actionId: resp.pendingActionId,
        summary: resp.pendingActionSummary || 'Lucien wants to perform an action on your behalf.',
        toolName: resp.pendingToolName,
      });
    }
  }, [speakReply]);

  const deliver = useCallback(async (raw: string, userMsgId: string) => {
    if (!sessionId) return;
    stopSpeaking();
    const controller = new AbortController();
    abortRef.current = controller;
    setSending(true);
    setError(null);
    try {
      const resp = await lucienApi.sendMessage({ sessionId, message: raw }, controller.signal);
      pushReply(resp);
    } catch (e) {
      if (controller.signal.aborted) {
        setToast('Stopped.');
      } else {
        console.error('Lucien sendMessage failed', e);
        setError(extractApiError(e, 'Lucien did not respond. Please try again or start a new chat.'));
        setMessages(prev => prev.filter(m => m.id !== userMsgId));
        setInput(raw);
      }
    } finally {
      abortRef.current = null;
      setSending(false);
      inputRef.current?.focus();
    }
  }, [sessionId, pushReply]);

  const send = useCallback(async (text?: string) => {
    const raw = (text ?? input).trim();
    if (!raw || !sessionId || sending || pendingConfirm) return;
    const now = Date.now();
    if (now - lastSentRef.current < SEND_DEBOUNCE_MS) return;
    lastSentRef.current = now;

    const id = `u-${now}`;
    setMessages(prev => [...prev, { id, role: 'USER', content: raw, createdAt: new Date().toISOString() }]);
    setInput('');
    setAtBottom(true);
    await deliver(raw, id);
  }, [input, sessionId, sending, pendingConfirm, deliver]);

  const stop = useCallback(() => { abortRef.current?.abort(); }, []);

  // Drop the last reply and ask again with the same prompt.
  const regenerate = useCallback(async () => {
    if (sending || !sessionId) return;
    const lastUser = [...messages].reverse().find(m => m.role === 'USER');
    if (!lastUser) return;
    setMessages(prev => {
      const out = [...prev];
      while (out.length && out[out.length - 1].role === 'ASSISTANT') out.pop();
      return out;
    });
    setAtBottom(true);
    await deliver(lastUser.content, lastUser.id);
  }, [messages, sending, sessionId, deliver]);

  // Rewrite a past prompt: everything after it is dropped, then it's re-sent.
  const editAndResend = useCallback(async (id: string, next: string) => {
    if (sending || !sessionId) return;
    const idx = messages.findIndex(m => m.id === id);
    if (idx < 0) return;
    const newId = `u-${Date.now()}`;
    setMessages(prev => [
      ...prev.slice(0, idx),
      { ...prev[idx], id: newId, content: next, createdAt: new Date().toISOString() },
    ]);
    setAtBottom(true);
    await deliver(next, newId);
  }, [messages, sending, sessionId, deliver]);

  const handleConfirmAction = useCallback(async (confirmed: boolean) => {
    if (!sessionId || !pendingConfirm) return;
    setConfirming(true);
    try {
      const resp = await lucienApi.confirmAction(sessionId, { actionId: pendingConfirm.actionId, confirmed });
      const replyText = resp.reply || (confirmed ? 'Action executed.' : 'Action cancelled.');
      setMessages(prev => [...prev, {
        id: resp.messageId ?? `a-${Date.now()}`,
        role: 'ASSISTANT',
        content: replyText,
        createdAt: resp.timestamp,
        modelName: resp.modelName,
      }]);
      speakReply(replyText);
    } catch (e) {
      console.error('Lucien confirmAction failed', e);
      setError(extractApiError(e, 'Failed to process confirmation. Please try again.'));
    } finally {
      setPendingConfirm(null);
      setConfirming(false);
      inputRef.current?.focus();
    }
  }, [sessionId, pendingConfirm, speakReply]);

  const newChat = useCallback(async () => {
    if (startingRef.current) return;
    abortRef.current?.abort();
    const prev = sessionId;
    setSessionId(null);
    setMessages([]);
    setInput('');
    setError(null);
    setPendingConfirm(null);
    setFeedback({});
    setHistoryOpen(false);
    if (prev) lucienApi.closeSession(prev).catch(() => {/* history is kept regardless */});
    await startSession();
  }, [sessionId, startSession]);

  // Reopen a past conversation from the history drawer.
  const openSession = useCallback(async (id: string) => {
    if (id === sessionId) { setHistoryOpen(false); return; }
    abortRef.current?.abort();
    setLoadingSession(true);
    setHistoryOpen(false);
    setError(null);
    setPendingConfirm(null);
    try {
      const rows = await lucienApi.getSessionHistory(id);
      setSessionId(id);
      setMessages(
        (rows ?? [])
          .filter(r => r.role !== 'SYSTEM')
          .map(r => ({
            id: r.id,
            role: r.role === 'USER' ? 'USER' : 'ASSISTANT',
            content: r.content,
            createdAt: r.createdAt,
            blocked: r.wasBlocked,
          })),
      );
      setAtBottom(true);
      window.setTimeout(() => scrollToBottom('auto'), 0);
    } catch (e) {
      console.error('Lucien openSession failed', e);
      setError(extractApiError(e, 'Failed to load that conversation. Please try again.'));
    } finally {
      setLoadingSession(false);
    }
  }, [sessionId, scrollToBottom]);

  const rateReply = useCallback((id: string, value: Feedback) => {
    setFeedback(prev => {
      const next = { ...prev };
      if (next[id] === value) delete next[id];
      else next[id] = value;
      return next;
    });
    setToast(value === 'up' ? 'Thanks — noted.' : 'Thanks — we\'ll use this to improve Lucien.');
  }, []);

  const isReady = !!sessionId && !starting;
  const busy = sending || confirming;
  const lastAssistantId = [...messages].reverse().find(m => m.role === 'ASSISTANT')?.id;
  const showFollowUps = !busy && !pendingConfirm && messages.length > 0
    && messages[messages.length - 1].role === 'ASSISTANT'
    && !messages[messages.length - 1].blocked;

  return (
    <aside ref={panelRef} className={`lucien-panel${open ? ' is-open' : ''}`} aria-label="Lucien AI assistant">
      <div className="lucien-panel-inner">

        <header className="lucien-panel-head">
          <button type="button" className="lucien-panel-icon-btn"
            onClick={() => setHistoryOpen(v => !v)}
            aria-label="Chat history" aria-expanded={historyOpen} title="Chat history">
            <History size={16} aria-hidden="true" />
          </button>

          <Logo height={34} className="lucien-panel-brand" />

          <div className="lucien-panel-head-actions">
            <button type="button" className="lucien-panel-icon-btn" onClick={newChat}
              disabled={starting} aria-label="New chat" title="New chat">
              <Plus size={15} aria-hidden="true" />
            </button>
            <button type="button" className="lucien-panel-icon-btn" onClick={onClose}
              aria-label="Close Lucien" title="Close">
              <X size={15} aria-hidden="true" />
            </button>
          </div>
        </header>

        <LucienHistory
          open={historyOpen}
          agentId={agentId}
          activeSessionId={sessionId}
          onClose={() => setHistoryOpen(false)}
          onOpenSession={openSession}
          onDeletedActive={newChat}
        />

        {error && (
          <div className="lucien-panel-error" role="alert">
            <AlertCircle size={15} aria-hidden="true" style={{ flexShrink: 0, marginTop: 1 }} />
            <div className="lucien-panel-error-body">
              <span className="lucien-panel-error-title">{error}</span>
            </div>
            <button type="button" className="lucien-panel-retry"
              onClick={() => { setError(null); if (!sessionId) startSession(); }} aria-label="Retry">
              <RefreshCw size={14} aria-hidden="true" />
            </button>
          </div>
        )}

        <div className="lucien-panel-body" ref={listRef} onScroll={onListScroll}>
          {messages.length === 0 && isReady && !error && !loadingSession && (
            <div className="lucien-panel-empty">
              <span className="lucien-panel-empty-mark">
                <img src={lucienLogo} alt="" aria-hidden="true" />
              </span>
              <p className="lucien-panel-empty-title">Ask Lucien</p>
              <p className="lucien-panel-empty-sub">
                Guidance on collection strategy, call and visit scripts, and the
                limits that apply. Lucien asks you to confirm before it acts on
                any account.
              </p>
              <div className="lucien-panel-prompts">
                {SAMPLE_PROMPTS.map(p => (
                  <button key={p.body} type="button" className="lucien-panel-prompt"
                    onClick={() => send(p.body)} disabled={busy}>
                    <span className="lucien-panel-prompt-title">{p.title}</span>
                    <span className="lucien-panel-prompt-body">{p.body}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {(starting || loadingSession) && messages.length === 0 && (
            <div className="lucien-panel-status">
              <Loader2 size={15} className="lucien-spin" aria-hidden="true" />
              {loadingSession ? 'Opening chat…' : 'Starting Lucien…'}
            </div>
          )}

          {messages.map(m => (
            <LucienMessage
              key={m.id}
              msg={m}
              initials={initialsOf(user)}
              isLast={m.id === lastAssistantId}
              busy={busy}
              feedback={feedback[m.id]}
              onFeedback={rateReply}
              onRegenerate={regenerate}
              onEdit={editAndResend}
            />
          ))}

          {sending && (
            <div className="lucien-row lucien-row--bot">
              <span className="lucien-avatar lucien-avatar--bot" aria-hidden="true">
                <img src={lucienLogo} alt="" />
              </span>
              <div className="lucien-row-main">
                <div className="lucien-bubble lucien-bubble--bot lucien-typing" aria-label="Lucien is typing">
                  <span className="lucien-dot" />
                  <span className="lucien-dot" />
                  <span className="lucien-dot" />
                </div>
              </div>
            </div>
          )}

          {showFollowUps && (
            <div className="lucien-followups">
              {FOLLOW_UPS.map(f => (
                <button key={f} type="button" className="lucien-followup" onClick={() => send(f)}>
                  {f}
                </button>
              ))}
            </div>
          )}
        </div>

        {!atBottom && messages.length > 0 && (
          <button type="button" className="lucien-jump" onClick={() => scrollToBottom()}
            aria-label="Jump to latest message">
            <ArrowDown size={14} aria-hidden="true" />
          </button>
        )}

        {pendingConfirm && (
          <div className="lucien-panel-confirm" role="alert">
            <ShieldAlert size={15} aria-hidden="true" style={{ flexShrink: 0, marginTop: 1 }} />
            <div className="lucien-panel-confirm-body">
              <span className="lucien-panel-confirm-title">
                Confirmation required{pendingConfirm.toolName ? ` — ${pendingConfirm.toolName}` : ''}
              </span>
              <span className="lucien-panel-confirm-sub">{pendingConfirm.summary}</span>
              <div className="lucien-panel-confirm-actions">
                <button type="button" className="lucien-panel-confirm-btn is-confirm"
                  onClick={() => handleConfirmAction(true)} disabled={confirming}>
                  {confirming ? <Loader2 size={12} className="lucien-spin" aria-hidden="true" /> : <Check size={12} aria-hidden="true" />} Confirm
                </button>
                <button type="button" className="lucien-panel-confirm-btn"
                  onClick={() => handleConfirmAction(false)} disabled={confirming}>
                  <X size={12} aria-hidden="true" /> Cancel
                </button>
              </div>
            </div>
          </div>
        )}

        <LucienComposer
          ref={inputRef}
          value={input}
          onChange={setInput}
          onSend={() => send()}
          onStop={stop}
          busy={sending}
          disabled={!isReady || !!pendingConfirm}
          placeholder={
            pendingConfirm ? 'Resolve the confirmation above…'
              : isReady ? 'Ask Lucien…'
              : 'Connecting…'
          }
          onUnavailable={what => setToast(/[.!?]$/.test(what) ? what : `${what} isn't available yet.`)}
          voiceLang={voiceLang}
          onVoiceLangChange={handleVoiceLangChange}
        />

        {toast && <div className="lucien-toast" role="status">{toast}</div>}
      </div>
    </aside>
  );
}
