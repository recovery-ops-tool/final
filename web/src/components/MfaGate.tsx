import { useState, useEffect, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { Lock, Check } from 'lucide-react';
import { profileSettings, useProfileSettingsOpen } from '../utils/profileSettings';
import { useSubscription } from '../hooks/useSubscription';
import './MfaGate.css';

function useRipple<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const fire = useCallback((e: React.MouseEvent) => {
    const el = ref.current; if (!el) return;
    const rect = el.getBoundingClientRect();
    const size = Math.max(rect.width, rect.height) * 2.2;
    const span = document.createElement('span');
    span.className = 'mfag-ripple';
    span.style.cssText = `width:${size}px;height:${size}px;left:${e.clientX - rect.left - size/2}px;top:${e.clientY - rect.top - size/2}px`;
    el.appendChild(span);
    span.addEventListener('animationend', () => span.remove(), { once: true });
  }, []);
  return { ref, fire };
}

const SNOOZE_KEY = 'rp-mfa-gate-snoozed-until';
const SNOOZE_MS  = 60 * 60 * 1000;
const RING_R = 21;
const CIRCUMFERENCE = 2 * Math.PI * RING_R;

interface MfaGateProps {
  mfaEnabled: boolean | undefined;
}

export default function MfaGate({ mfaEnabled }: MfaGateProps) {
  const settingsOpen = useProfileSettingsOpen();
  const { sub, loading, isExpired, isPlatformAdmin } = useSubscription();
  const [visible,   setVisible]   = useState(false);
  const [elapsed,   setElapsed]   = useState(0); // 0..1 fraction
  const rafRef    = useRef<number | null>(null);
  const startRef  = useRef<number>(0);
  const cta = useRipple<HTMLButtonElement>();

  useEffect(() => {
    const subSnoozedUntil = Number(localStorage.getItem('rp-sub-expired-snoozed-until') ?? 0);
    const isSubModalVisible = !loading && !isPlatformAdmin && !!sub && isExpired && subSnoozedUntil <= Date.now();

    if (mfaEnabled || settingsOpen || isSubModalVisible) {
      setVisible(false);
      return;
    }
    const snoozedUntil = Number(localStorage.getItem(SNOOZE_KEY) ?? 0);
    if (snoozedUntil && snoozedUntil > Date.now()) {
      setVisible(false);
      const t = window.setTimeout(() => setVisible(true), snoozedUntil - Date.now());
      return () => window.clearTimeout(t);
    }
    setVisible(true);
  }, [mfaEnabled, settingsOpen, loading, isPlatformAdmin, sub, isExpired]);

  useEffect(() => {
    if (!visible) return;
    setElapsed(0);
    startRef.current = performance.now();
    const DURATION = 15_000;
    const tick = (now: number) => {
      const fraction = Math.min((now - startRef.current) / DURATION, 1);
      setElapsed(fraction);
      if (fraction < 1) rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); };
  }, [visible]);

  const onClose = () => {
    try { localStorage.setItem(SNOOZE_KEY, String(Date.now() + SNOOZE_MS)); } catch {}
    setVisible(false);
  };

  if (!visible) return null;

  const countdown  = Math.ceil((1 - elapsed) * 15);
  const dashOffset = CIRCUMFERENCE * (1 - elapsed);
  const isLocked   = elapsed < 1;
  const ringColor   = isLocked ? 'var(--warning)' : 'color-mix(in srgb, var(--text-primary) 35%, transparent)';

  return createPortal(
    <div
      className="mfag-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="mfag-title"
      aria-describedby="mfag-desc"
      onClick={e => { if (e.target === e.currentTarget && !isLocked) onClose(); }}
    >
      <div className="mfag-card">

        {/* Lock icon with countdown ring orbiting it */}
        <div className="mfag-top-row">
          <div style={{ position: 'relative', display: 'inline-flex', marginBottom: 0 }}>
            <div className="mfag-icon" aria-hidden="true">
              <Lock size={18} />
            </div>
            <svg width={48} height={48} viewBox="0 0 48 48"
              aria-label={`Later available in ${countdown}s`}
              style={{ position: 'absolute', top: -4, left: -4, pointerEvents: 'none' }}>
              <circle cx={24} cy={24} r={RING_R} fill="none" stroke="color-mix(in srgb, var(--text-primary) 10%, transparent)" strokeWidth={1.5} />
              <circle
                cx={24} cy={24} r={RING_R} fill="none"
                stroke={ringColor} strokeWidth={1.5}
                strokeLinecap="round"
                strokeDasharray={CIRCUMFERENCE}
                strokeDashoffset={dashOffset}
                transform="rotate(-90 24 24)"
                style={{ transition: 'stroke 0.4s' }}
              />
            </svg>
          </div>
        </div>

        <h3 className="mfag-title" id="mfag-title">Secure your account</h3>
        <p className="mfag-sub" id="mfag-desc">
          Two-factor authentication adds a second layer of protection to your account.
        </p>

        <div className="mfag-checks" aria-label="Benefits">
          <span><Check size={11} aria-hidden="true" />Blocks unauthorized sign-ins</span>
          <span><Check size={11} aria-hidden="true" />Under 2 minutes to set up</span>
          <span><Check size={11} aria-hidden="true" />Any authenticator app</span>
        </div>

        <div className="mfag-actions">
          <button
            type="button"
            className="mfag-btn-cancel"
            onClick={onClose}
            disabled={isLocked}
            title={isLocked ? `Available in ${countdown}s` : undefined}
          >
            Later
          </button>
          <button
            ref={cta.ref}
            type="button"
            className="mfag-btn-primary"
            onClick={e => { cta.fire(e); profileSettings.show('security'); }}
          >
            Enable now
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
