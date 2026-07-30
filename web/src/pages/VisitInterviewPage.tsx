import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { apiClient, unwrapApiResponse } from '../client';
import { useAuth } from '../AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import type { AllocationResponse } from '../types';
import { ArrowLeft, AlertCircle, Check, Loader2, ShieldAlert, X } from 'lucide-react';
import { lucienApi, type VoiceLang } from '../api/lucienApi';
import { extractApiError } from '../utils/extractApiError';
import { LucienMessage, type ChatMessage } from '../components/LucienMessage';
import { LucienComposer } from '../components/LucienComposer';
import { GpsChecklist, PhotosChecklist, type Coords } from './VisitSubmitHelpers';
import lucienLogo from '../assets/images/lucien-logo.png';
import '../components/LucienPanel.css';
import '../styles/AppPage.css';
import '../pages/Dashboard.css';
import './VisitInterviewPage.css';

const fadeUp: Variants = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: { duration: 0.38, ease: [0.22, 1, 0.36, 1] as [number, number, number, number] } },
};
const fadeIn: Variants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { duration: 0.24, ease: 'easeOut' as const } },
};

const SUBMIT_TOOL = 'submit_visit_interview';

interface PendingConfirm {
  actionId: string;
  summary: string;
  toolName?: string;
}

function initialsOf(user?: { firstName?: string; lastName?: string; email?: string } | null) {
  if (user?.firstName && user?.lastName) return (user.firstName[0] + user.lastName[0]).toUpperCase();
  if (user?.firstName) return user.firstName.slice(0, 2).toUpperCase();
  if (user?.email) return user.email[0].toUpperCase();
  return '?';
}

export default function VisitInterviewPage() {
  const { caseId } = useParams<{ caseId: string }>();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { hasPermission } = usePermissions();
  const canSubmit = hasPermission('VISIT_SUBMIT');

  const [allocation, setAllocation] = useState<AllocationResponse | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const [coords, setCoords] = useState<Coords | null>(null);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [selfieImage, setSelfieImage] = useState<File | null>(null);
  const [envImage, setEnvImage] = useState<File | null>(null);

  const selfieRef = useRef<HTMLInputElement>(null);
  const envRef = useRef<HTMLInputElement>(null);
  const watchRef = useRef<number | null>(null);

  const isMobile = /Mobi|Android/i.test(navigator.userAgent);
  const GPS_ACCURACY_THRESHOLD_M = isMobile ? 50 : 200;
  const gpsAccurate = !!coords && coords.accuracy <= GPS_ACCURACY_THRESHOLD_M;
  const photosOk = !!selfieImage && !!envImage;

  const startGpsWatch = useCallback(() => {
    if (!('geolocation' in navigator)) { setGpsError('GPS not available on this device.'); return; }
    if (watchRef.current !== null) { navigator.geolocation.clearWatch(watchRef.current); watchRef.current = null; }
    watchRef.current = navigator.geolocation.watchPosition(
      (pos) => { setCoords({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy }); setGpsError(null); },
      (err) => {
        setGpsError(err.message || 'GPS permission denied.');
        if (watchRef.current !== null) { navigator.geolocation.clearWatch(watchRef.current); watchRef.current = null; }
      },
      { enableHighAccuracy: true, maximumAge: 5_000, timeout: 15_000 },
    );
  }, []);

  const requestGps = useCallback(() => {
    setGpsError(null);
    setCoords(null);
    startGpsWatch();
  }, [startGpsWatch]);

  useEffect(() => {
    if (!caseId) return;
    apiClient.get(`/api/v1/allocations/${caseId}`)
      .then(({ data }) => setAllocation(unwrapApiResponse<AllocationResponse>(data)))
      .catch(() => setPageError('Could not load this case.'));
    startGpsWatch();
    return () => { if (watchRef.current !== null) { navigator.geolocation.clearWatch(watchRef.current); watchRef.current = null; } };
  }, [caseId, startGpsWatch]);

  // ── Lucien chat, scoped to this case ─────────────────────────────────────
  const agentId = user?.agentId || user?.id || '';
  const agentFirstName = user?.firstName || 'Agent';

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [starting, setStarting] = useState(false);
  const [chatError, setChatError] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirm | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [voiceLang, setVoiceLang] = useState<VoiceLang>('en');
  const [toast, setToast] = useState<string | null>(null);

  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const startingRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'smooth') => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior });
  }, []);
  useEffect(() => { scrollToBottom(); }, [messages, sending, scrollToBottom]);

  useEffect(() => {
    if (!toast) return;
    const id = window.setTimeout(() => setToast(null), 2600);
    return () => window.clearTimeout(id);
  }, [toast]);

  const startSession = useCallback(async () => {
    if (startingRef.current || !caseId || !agentId) return;
    startingRef.current = true;
    setStarting(true);
    setChatError(null);
    try {
      const session = await lucienApi.startSession({ agentId, agentFirstName, allocationId: caseId });
      setSessionId(session.sessionId);
    } catch (e) {
      setChatError(extractApiError(e, 'Lucien is unavailable right now. Please try again, or fill the form manually.'));
    } finally {
      setStarting(false);
      startingRef.current = false;
    }
  }, [caseId, agentId, agentFirstName]);

  useEffect(() => {
    if (!sessionId && !startingRef.current && !chatError) startSession();
  }, [sessionId, chatError, startSession]);

  const pushReply = useCallback((resp: Awaited<ReturnType<typeof lucienApi.sendMessage>>) => {
    if (resp.blocked) {
      setMessages(prev => [...prev, {
        id: resp.messageId ?? `a-${Date.now()}`, role: 'ASSISTANT', content: '',
        blocked: true, blockReason: resp.blockReason, createdAt: resp.timestamp,
      }]);
      return;
    }
    setMessages(prev => [...prev, {
      id: resp.messageId ?? `a-${Date.now()}`, role: 'ASSISTANT',
      content: resp.reply || 'Lucien had nothing to add.', createdAt: resp.timestamp, modelName: resp.modelName,
    }]);
    if (resp.confirmationRequired && resp.pendingActionId) {
      setPendingConfirm({
        actionId: resp.pendingActionId,
        summary: resp.pendingActionSummary || 'Lucien wants to submit this visit.',
        toolName: resp.pendingToolName,
      });
    }
  }, []);

  const deliver = useCallback(async (raw: string, userMsgId: string) => {
    if (!sessionId) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setSending(true);
    setChatError(null);
    try {
      const resp = await lucienApi.sendMessage({ sessionId, message: raw }, controller.signal);
      pushReply(resp);
    } catch (e) {
      if (!controller.signal.aborted) {
        setChatError(extractApiError(e, 'Lucien did not respond. Please try again.'));
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
    const id = `u-${Date.now()}`;
    setMessages(prev => [...prev, { id, role: 'USER', content: raw, createdAt: new Date().toISOString() }]);
    setInput('');
    await deliver(raw, id);
  }, [input, sessionId, sending, pendingConfirm, deliver]);

  const stop = useCallback(() => { abortRef.current?.abort(); }, []);

  const regenerate = useCallback(async () => {
    if (sending || !sessionId) return;
    const lastUser = [...messages].reverse().find(m => m.role === 'USER');
    if (!lastUser) return;
    setMessages(prev => {
      const out = [...prev];
      while (out.length && out[out.length - 1].role === 'ASSISTANT') out.pop();
      return out;
    });
    await deliver(lastUser.content, lastUser.id);
  }, [messages, sending, sessionId, deliver]);

  const editAndResend = useCallback(async (id: string, next: string) => {
    if (sending || !sessionId) return;
    const idx = messages.findIndex(m => m.id === id);
    if (idx < 0) return;
    const newId = `u-${Date.now()}`;
    setMessages(prev => [...prev.slice(0, idx), { ...prev[idx], id: newId, content: next, createdAt: new Date().toISOString() }]);
    await deliver(next, newId);
  }, [messages, sending, sessionId, deliver]);

  // Confirming submit_visit_interview needs the GPS fix + photos held on this page (never
  // routed through Lucien); any other WRITE tool Lucien might call goes through the plain
  // JSON confirm endpoint instead.
  const handleConfirm = useCallback(async (confirmed: boolean) => {
    if (!sessionId || !pendingConfirm) return;
    setConfirming(true);
    try {
      if (pendingConfirm.toolName === SUBMIT_TOOL) {
        if (confirmed && (!coords || !gpsAccurate || !photosOk)) {
          setChatError('GPS lock and both photos are required before submitting.');
          setConfirming(false);
          return;
        }
        const resp = await lucienApi.confirmVisitAction(
          sessionId,
          {
            actionId: pendingConfirm.actionId,
            confirmed,
            latitude: coords?.lat,
            longitude: coords?.lng,
            gpsAccuracy: coords?.accuracy,
          },
          confirmed ? selfieImage : null,
          confirmed ? envImage : null,
        );
        const replyText = resp.reply || (confirmed ? 'Visit submitted.' : 'Action cancelled.');
        setMessages(prev => [...prev, { id: resp.messageId ?? `a-${Date.now()}`, role: 'ASSISTANT', content: replyText, createdAt: resp.timestamp }]);
        if (confirmed && !resp.confirmationRequired && replyText.startsWith('Done.')) {
          setSubmitted(true);
        }
      } else {
        const resp = await lucienApi.confirmAction(sessionId, { actionId: pendingConfirm.actionId, confirmed });
        const replyText = resp.reply || (confirmed ? 'Action executed.' : 'Action cancelled.');
        setMessages(prev => [...prev, { id: resp.messageId ?? `a-${Date.now()}`, role: 'ASSISTANT', content: replyText, createdAt: resp.timestamp }]);
      }
    } catch (e) {
      setChatError(extractApiError(e, 'Failed to process confirmation. Please try again.'));
    } finally {
      setPendingConfirm(null);
      setConfirming(false);
      inputRef.current?.focus();
    }
  }, [sessionId, pendingConfirm, coords, gpsAccurate, photosOk, selfieImage, envImage]);

  useEffect(() => {
    if (!submitted) return;
    const id = window.setTimeout(() => navigate('/app/today', { replace: true }), 1400);
    return () => window.clearTimeout(id);
  }, [submitted, navigate]);

  const isReady = !!sessionId && !starting;
  const busy = sending || confirming;
  const lastAssistantId = [...messages].reverse().find(m => m.role === 'ASSISTANT')?.id;

  return (
    <div className="dd-page" style={{ background: 'var(--bg-canvas)' }}>
      <div className="dd-page-header">
        <div className="dd-page-titles">
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <button type="button" onClick={() => navigate(-1)} className="ds-btn is-secondary is-sm">
              <ArrowLeft size={16} />
            </button>
            <h1 className="dd-page-title" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              Visit Interview
              <span className="dd-page-context">Lucien will interview you through this visit and submit it</span>
            </h1>
          </div>
        </div>
        <div className="dd-page-actions">
          {caseId && (
            <Link to={`/app/visits/${caseId}/submit`} className="ds-btn is-secondary is-sm">
              Fill form manually
            </Link>
          )}
        </div>
      </div>

      <div className="dd-main-container" style={{ overflow: 'hidden', padding: '0 8px 24px' }}>
        {pageError && (
          <div className="dd-error-banner" role="alert" style={{ maxWidth: '1200px', margin: '0 auto 16px' }}>
            <AlertCircle size={16} className="dd-error-icon" />
            <div className="dd-error-body">
              <span className="dd-error-title">{pageError}</span>
            </div>
          </div>
        )}
        <div style={{ display: 'grid', gridTemplateColumns: '340px 1fr', gap: '24px', maxWidth: '1200px', margin: '0 auto', height: '100%', alignItems: 'stretch' }}>

          {/* ── Left: case context, GPS, photos ── */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px', overflowY: 'auto' }}>
            {allocation && (
              <motion.div variants={fadeIn} initial="hidden" animate="show" className="ds-card is-pad">
                <div className="ds-card-head" style={{ justifyContent: 'flex-start', margin: 0, padding: 0, border: 'none' }}>
                  <div className="ds-card-title" style={{ fontSize: 18 }}>{allocation.borrowerName}</div>
                  <div className="ds-card-sub" style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 4 }}>
                    {allocation.loanNumber && <span>Loan #{allocation.loanNumber}</span>}
                  </div>
                </div>
              </motion.div>
            )}

            <motion.div variants={fadeUp} initial="hidden" animate="show">
              <GpsChecklist
                coords={coords} gpsError={gpsError} gpsAccurate={gpsAccurate}
                isMobile={isMobile} GPS_ACCURACY_THRESHOLD_M={GPS_ACCURACY_THRESHOLD_M}
                onRetry={requestGps}
              />
            </motion.div>

            <motion.div variants={fadeUp} initial="hidden" animate="show">
              <PhotosChecklist
                selfieImage={selfieImage} envImage={envImage}
                selfieRef={selfieRef} envRef={envRef}
                onCaptureSelfie={(files) => setSelfieImage(files?.[0] ?? null)}
                onCaptureEnv={(files) => setEnvImage(files?.[0] ?? null)}
                onRemoveSelfie={() => setSelfieImage(null)}
                onRemoveEnv={() => setEnvImage(null)}
              />
            </motion.div>

            {!canSubmit && (
              <div className="ds-card is-pad" style={{ background: 'var(--warning-subtle)', borderColor: 'var(--warning-border)', fontSize: 13 }}>
                You don't have permission to submit visits.
              </div>
            )}
          </div>

          {/* ── Right: Lucien chat ── */}
          <div className="lucien-panel is-embedded is-open" aria-label="Lucien visit interview">
            <div className="lucien-panel-inner">
              {chatError && (
                <div className="lucien-panel-error" role="alert">
                  <AlertCircle size={15} aria-hidden="true" style={{ flexShrink: 0, marginTop: 1 }} />
                  <div className="lucien-panel-error-body">
                    <span className="lucien-panel-error-title">{chatError}</span>
                  </div>
                  {!sessionId && (
                    <button type="button" className="lucien-panel-retry" onClick={() => { setChatError(null); startSession(); }} aria-label="Retry">
                      Retry
                    </button>
                  )}
                </div>
              )}

              <div className="lucien-panel-body" ref={listRef}>
                {messages.length === 0 && isReady && !chatError && (
                  <div className="lucien-panel-empty">
                    <span className="lucien-panel-empty-mark">
                      <img src={lucienLogo} alt="" aria-hidden="true" />
                    </span>
                    <p className="lucien-panel-empty-title">Lucien is standing in as your manager for this visit</p>
                    <p className="lucien-panel-empty-sub">
                      Tell Lucien what's happening at the door and it'll coach you through it,
                      then submit the visit once a disposition is reached.
                    </p>
                  </div>
                )}

                {starting && messages.length === 0 && (
                  <div className="lucien-panel-status">
                    <Loader2 size={15} className="lucien-spin" aria-hidden="true" />
                    Starting Lucien…
                  </div>
                )}

                {messages.map(m => (
                  <LucienMessage
                    key={m.id}
                    msg={m}
                    initials={initialsOf(user)}
                    isLast={m.id === lastAssistantId}
                    busy={busy}
                    onFeedback={() => {}}
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
                        <span className="lucien-dot" /><span className="lucien-dot" /><span className="lucien-dot" />
                      </div>
                    </div>
                  </div>
                )}

                {submitted && (
                  <div className="lucien-row lucien-row--bot">
                    <span className="lucien-avatar lucien-avatar--bot" aria-hidden="true">
                      <img src={lucienLogo} alt="" />
                    </span>
                    <div className="lucien-row-main">
                      <div className="lucien-bubble lucien-bubble--bot" style={{ color: 'var(--success)' }}>
                        <Check size={14} style={{ marginRight: 6, verticalAlign: '-2px' }} />
                        Visit submitted — returning to today's visits…
                      </div>
                    </div>
                  </div>
                )}
              </div>

              <AnimatePresence>
                {pendingConfirm && !submitted && (
                  <motion.div className="lucien-panel-confirm" role="alert"
                    initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 8 }}>
                    <ShieldAlert size={15} aria-hidden="true" style={{ flexShrink: 0, marginTop: 1 }} />
                    <div className="lucien-panel-confirm-body">
                      <span className="lucien-panel-confirm-title">
                        {pendingConfirm.toolName === SUBMIT_TOOL ? 'Ready to submit this visit' : 'Confirmation required'}
                      </span>
                      <span className="lucien-panel-confirm-sub">{pendingConfirm.summary}</span>
                      {pendingConfirm.toolName === SUBMIT_TOOL && (!gpsAccurate || !photosOk) && (
                        <span className="lucien-panel-confirm-sub" style={{ color: 'var(--warning)' }}>
                          {!gpsAccurate ? 'Waiting for an accurate GPS lock. ' : ''}
                          {!photosOk ? 'Selfie and site photo are required.' : ''}
                        </span>
                      )}
                      <div className="lucien-panel-confirm-actions">
                        <button type="button" className="lucien-panel-confirm-btn is-confirm"
                          onClick={() => handleConfirm(true)}
                          disabled={confirming || (pendingConfirm.toolName === SUBMIT_TOOL && (!gpsAccurate || !photosOk))}>
                          {confirming ? <Loader2 size={12} className="lucien-spin" aria-hidden="true" /> : <Check size={12} aria-hidden="true" />} Confirm
                        </button>
                        <button type="button" className="lucien-panel-confirm-btn"
                          onClick={() => handleConfirm(false)} disabled={confirming}>
                          <X size={12} aria-hidden="true" /> Cancel
                        </button>
                      </div>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>

              <LucienComposer
                ref={inputRef}
                value={input}
                onChange={setInput}
                onSend={() => send()}
                onStop={stop}
                busy={sending}
                disabled={!isReady || !!pendingConfirm || submitted}
                placeholder={
                  submitted ? 'Visit submitted.'
                    : pendingConfirm ? 'Resolve the confirmation above…'
                      : isReady ? 'Tell Lucien what\'s happening…'
                        : 'Connecting…'
                }
                onUnavailable={what => setToast(/[.!?]$/.test(what) ? what : `${what} isn't available yet.`)}
                voiceLang={voiceLang}
                onVoiceLangChange={setVoiceLang}
              />

              {toast && <div className="lucien-toast" role="status">{toast}</div>}
            </div>
          </div>

        </div>
      </div>
    </div>
  );
}
