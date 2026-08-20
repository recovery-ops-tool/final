import { useState, useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { authApi } from '../api';
import { Loader2, AlertCircle, CheckCircle2, ChevronLeft, MoveRight, ShieldCheck, Clock, KeyRound } from 'lucide-react';
import { LoginBrandPanel } from './LoginBrandPanel';
import { OtpBoxes } from './OtpBoxes';
import { PasswordStrength, EyeOpen, EyeClosed } from './ResetPasswordHelpers';
import '../styles/LoginPage.css';

type Step = 'email' | 'otp' | 'password' | 'success';

const emailSchema = z.object({
  email: z.string().min(1, 'Email is required').email('Enter a valid email address'),
});

const passwordSchema = z.object({
  newPassword: z.string()
    .min(8, 'At least 8 characters')
    .regex(/[A-Z]/, 'One uppercase letter')
    .regex(/[a-z]/, 'One lowercase letter')
    .regex(/[0-9]/, 'One number')
    .regex(/[@$!%*?&]/, 'One special character (@$!%*?&)'),
  confirmPassword: z.string().min(1, 'Please confirm your password'),
}).refine(d => d.newPassword === d.confirmPassword, {
  message: 'Passwords do not match',
  path: ['confirmPassword'],
});

type EmailForm    = z.infer<typeof emailSchema>;
type PasswordForm = z.infer<typeof passwordSchema>;

export default function ForgotPasswordPage() {
  const [isVisible,      setIsVisible]      = useState(false);
  const [step,           setStep]           = useState<Step>('email');
  const [email,          setEmail]          = useState('');
  const [otp,            setOtp]            = useState('');
  const [otpError,       setOtpError]       = useState<string | null>(null);
  const [emailError,     setEmailError]     = useState<string | null>(null);
  const [isLoading,      setIsLoading]      = useState(false);
  const [resendMsg,      setResendMsg]      = useState<string | null>(null);
  const [resendCooldown, setResendCooldown] = useState(0);
  const [showNew,        setShowNew]        = useState(false);
  const [showConfirmPw,  setShowConfirmPw]  = useState(false);
  const [showConfirmDlg, setShowConfirmDlg] = useState(false);
  const [serverError,    setServerError]    = useState<string | null>(null);
  const [rateLimitMsg,   setRateLimitMsg]   = useState<string | null>(null);

  useEffect(() => { requestAnimationFrame(() => setIsVisible(true)); }, []);

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const id = setTimeout(() => setResendCooldown(c => c - 1), 1000);
    return () => clearTimeout(id);
  }, [resendCooldown]);

  const emailForm = useForm<EmailForm>({ resolver: zodResolver(emailSchema) });
  const pwForm    = useForm<PasswordForm>({ resolver: zodResolver(passwordSchema) });
  const newPassword = pwForm.watch('newPassword') ?? '';

  // ── Step 1: send OTP ─────────────────────────────────────────────────────
  const onSubmitEmail = async (data: EmailForm) => {
    setIsLoading(true);
    setEmailError(null);
    setRateLimitMsg(null);
    try {
      await authApi.forgotPassword({ email: data.email });
      setEmail(data.email);
      setStep('otp');
      setResendCooldown(30);
    } catch (err: any) {
      const status = err?.response?.status;
      const retryAfter: number | undefined = err?.response?.data?.retryAfterSeconds;
      if (status === 404) {
        setEmailError('No account found with this email address.');
      } else if (status === 429) {
        setRateLimitMsg(retryAfter
          ? `Too many requests. Please wait ${retryAfter} seconds before trying again.`
          : 'Too many requests. Please wait a few minutes before trying again.');
      } else {
        setEmailError('Something went wrong. Please try again.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  // ── Step 2: verify OTP ───────────────────────────────────────────────────
  const onVerifyOtp = async () => {
    if (otp.length < 6) return;
    setIsLoading(true);
    setOtpError(null);
    setRateLimitMsg(null);
    try {
      await authApi.verifyResetOtp({ email, otp });
      setStep('password');
    } catch (err: any) {
      const status = err?.response?.status;
      const retryAfter: number | undefined = err?.response?.data?.retryAfterSeconds;
      if (status === 400) {
        setOtpError('Invalid or expired code. Check the code and try again.');
        setOtp('');
      } else if (status === 429) {
        setRateLimitMsg(retryAfter
          ? `Too many verification attempts. Please wait ${retryAfter} seconds before trying again.`
          : 'Too many verification attempts. Please wait a few minutes before trying again.');
      } else {
        setOtpError('Verification failed. Please try again.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  // ── Resend OTP ────────────────────────────────────────────────────────────
  const onResend = async () => {
    if (resendCooldown > 0) return;
    setResendMsg(null);
    try {
      await authApi.forgotPassword({ email });
      setResendMsg('New code sent. Check your inbox.');
      setResendCooldown(30);
    } catch {
      setResendMsg('Could not resend. Please try again.');
    }
  };

  // ── Step 3: reset password ────────────────────────────────────────────────
  const onSubmitPassword = async (data: PasswordForm) => {
    setShowConfirmDlg(false);
    setIsLoading(true);
    setServerError(null);
    setRateLimitMsg(null);
    try {
      await authApi.resetPassword({ email, otp, newPassword: data.newPassword });
      setStep('success');
    } catch (err: any) {
      const status = err?.response?.status;
      const msg    = err?.response?.data?.message;
      const retryAfter: number | undefined = err?.response?.data?.retryAfterSeconds;
      if (status === 400) {
        setOtpError('Invalid or expired code. Please enter the correct code.');
        setOtp('');
        setStep('otp');
      } else if (status === 429) {
        setRateLimitMsg(retryAfter
          ? `Too many attempts. Please wait ${retryAfter} seconds before trying again.`
          : 'Too many attempts. Please wait a few minutes before trying again.');
      } else {
        setServerError(msg || 'Something went wrong. Please try again.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="signin-page" style={{ opacity: isVisible ? 1 : 0, transition: 'opacity 300ms ease-out' }}>
      <main className="page" role="main">
        <LoginBrandPanel />

        <section className="right" aria-label="Forgot password">
          <div className="form-wrap">

            {/* ── Rate limit banner — shown across all steps ── */}
            {rateLimitMsg && (
              <div className="banner banner-warn" role="alert">
                <Clock size={15} />
                <span>{rateLimitMsg}</span>
              </div>
            )}

            {/* ── Step 1: Email ── */}
            {step === 'email' && (
              <>
                <header className="form-header">
                  <p className="rp-step-label">Account recovery · Step 1 of 3</p>
                  <h2 className="form-title">Forgot your password?</h2>
                  <p className="form-sub">Enter the email address linked to your account and we'll send you a reset code.</p>
                </header>

                <form onSubmit={emailForm.handleSubmit(onSubmitEmail)} noValidate>
                  <div className="field-group">
                    <div className="field-label-row">
                      <label className="field-label" htmlFor="fp-email">Email</label>
                    </div>
                    <div className="input-wrap">
                      <input
                        className={`input${emailForm.formState.errors.email || emailError ? ' error' : ''}`}
                        type="email"
                        id="fp-email"
                        autoComplete="email"
                        placeholder="you@company.com"
                        aria-required="true"
                        autoFocus
                        {...emailForm.register('email')}
                        onChange={e => { setEmailError(null); emailForm.register('email').onChange(e); }}
                      />
                    </div>
                    {emailForm.formState.errors.email && (
                      <p className="field-helper error visible" role="alert">
                        <AlertCircle size={12} /> {emailForm.formState.errors.email.message}
                      </p>
                    )}
                    {emailError && !emailForm.formState.errors.email && (
                      <p className="field-helper error visible" role="alert">
                        <AlertCircle size={12} /> {emailError}
                      </p>
                    )}
                  </div>

                  <button type="submit" className={`btn-primary${isLoading ? ' loading' : ''}`} disabled={isLoading}>
                    {isLoading
                      ? <><Loader2 size={15} className="spinner-icon" /><span className="btn-primary-text">Checking…</span></>
                      : <><span className="btn-primary-text">Send reset code</span><MoveRight size={18} /></>
                    }
                  </button>

                  <a href="/login" className="link-back"><ChevronLeft size={14} /> Back to sign in</a>

                  <footer className="form-footer">
                    <p className="form-footer-legal"><a href="/privacy">Privacy</a> &nbsp;·&nbsp; <a href="/terms">Terms</a></p>
                  </footer>
                </form>
              </>
            )}

            {/* ── Step 2: OTP ── */}
            {step === 'otp' && (
              <>
                <header className="form-header">
                  <p className="rp-step-label">Account recovery · Step 2 of 3</p>
                  <div className="mfa-icon-row">
                    <div className="mfa-icon-badge"><KeyRound size={20} /></div>
                    <h2 className="form-title">Enter the reset code</h2>
                  </div>
                  <p className="form-sub">
                    We sent a 6-digit code to <span className="mfa-email">{email}</span>. Check your spam folder if you don't see it.
                  </p>
                </header>

                <div className="field-group">
                  <div className="field-label-row">
                    <label className="field-label">Reset code</label>
                    <button
                      type="button"
                      className="forgot-link"
                      style={{ fontSize: 12 }}
                      onClick={onResend}
                      disabled={resendCooldown > 0}
                    >
                      {resendCooldown > 0 ? `Resend code in ${resendCooldown}s` : 'Resend code'}
                    </button>
                  </div>
                  <OtpBoxes value={otp} onChange={v => { setOtp(v); setOtpError(null); }} />
                  {otpError && (
                    <p className="field-helper error visible" role="alert">
                      <AlertCircle size={12} /> {otpError}
                    </p>
                  )}
                  {resendMsg && !otpError && (
                    <p className="field-helper muted visible">{resendMsg}</p>
                  )}
                </div>

                <button
                  type="button"
                  className={`btn-primary${isLoading ? ' loading' : ''}`}
                  disabled={otp.length < 6 || isLoading}
                  onClick={onVerifyOtp}
                >
                  {isLoading
                    ? <><Loader2 size={15} className="spinner-icon" /><span className="btn-primary-text">Verifying…</span></>
                    : <><span className="btn-primary-text">Continue</span><MoveRight size={18} /></>
                  }
                </button>

                <button type="button" className="link-back"
                  onClick={() => { setStep('email'); setOtp(''); setOtpError(null); setResendMsg(null); setResendCooldown(0); }}>
                  <ChevronLeft size={14} /> Use a different email
                </button>

                <footer className="form-footer">
                  <p className="form-footer-legal"><a href="/privacy">Privacy</a> &nbsp;·&nbsp; <a href="/terms">Terms</a></p>
                </footer>
              </>
            )}

            {/* ── Step 3: New password ── */}
            {step === 'password' && (
              <>
                <header className="form-header">
                  <p className="rp-step-label">Account recovery · Step 3 of 3</p>
                  <h2 className="form-title">Set a new password</h2>
                  <p className="form-sub">
                    Choose a strong password for <span className="mfa-email">{email}</span>.
                  </p>
                </header>

                {serverError && (
                  <div className="banner banner-error" role="alert">
                    <AlertCircle size={15} /><span>{serverError}</span>
                  </div>
                )}

                <form onSubmit={pwForm.handleSubmit(() => setShowConfirmDlg(true))} noValidate>
                  <div className="field-group">
                    <div className="field-label-row">
                      <label className="field-label" htmlFor="fp-new">New password</label>
                    </div>
                    <div className="input-wrap">
                      <input
                        id="fp-new"
                        className={`input${pwForm.formState.errors.newPassword ? ' error' : ''}`}
                        type={showNew ? 'text' : 'password'}
                        autoComplete="new-password"
                        placeholder="Create a strong password"
                        autoFocus
                        {...pwForm.register('newPassword')}
                      />
                      <div className="input-suffix">
                        <button type="button" className="pw-toggle"
                          onClick={() => setShowNew(v => !v)}
                          aria-label={showNew ? 'Hide password' : 'Show password'}>
                          {showNew ? EyeClosed : EyeOpen}
                        </button>
                      </div>
                    </div>
                    {pwForm.formState.errors.newPassword && (
                      <p className="field-helper error visible" role="alert">
                        {pwForm.formState.errors.newPassword.message}
                      </p>
                    )}
                    <PasswordStrength password={newPassword} />
                  </div>

                  <div className="field-group">
                    <div className="field-label-row">
                      <label className="field-label" htmlFor="fp-confirm">Confirm password</label>
                    </div>
                    <div className="input-wrap">
                      <input
                        id="fp-confirm"
                        className={`input${pwForm.formState.errors.confirmPassword ? ' error' : ''}`}
                        type={showConfirmPw ? 'text' : 'password'}
                        autoComplete="new-password"
                        placeholder="Re-enter your new password"
                        {...pwForm.register('confirmPassword')}
                      />
                      <div className="input-suffix">
                        <button type="button" className="pw-toggle"
                          onClick={() => setShowConfirmPw(v => !v)}
                          aria-label={showConfirmPw ? 'Hide password' : 'Show password'}>
                          {showConfirmPw ? EyeClosed : EyeOpen}
                        </button>
                      </div>
                    </div>
                    {pwForm.formState.errors.confirmPassword && (
                      <p className="field-helper error visible" role="alert">
                        {pwForm.formState.errors.confirmPassword.message}
                      </p>
                    )}
                  </div>

                  <button type="submit" className="btn-primary">
                    <span className="btn-primary-text">Change password</span>
                    <MoveRight size={18} />
                  </button>

                  <footer className="form-footer">
                    <p className="form-footer-legal"><a href="/privacy">Privacy</a> &nbsp;·&nbsp; <a href="/terms">Terms</a></p>
                  </footer>
                </form>
              </>
            )}

            {/* ── Step 4: Success ── */}
            {step === 'success' && (
              <>
                <header className="form-header">
                  <div className="mfa-icon-row">
                    <div className="mfa-icon-badge"><CheckCircle2 size={20} /></div>
                    <h2 className="form-title">Password updated</h2>
                  </div>
                  <p className="form-sub">Your password has been changed successfully. Sign in with your new credentials.</p>
                </header>

                <a href="/login?reason=password_changed" className="btn-primary">
                  <span className="btn-primary-text">Back to sign in</span>
                  <MoveRight size={18} />
                </a>

                <footer className="form-footer">
                  <p className="form-footer-legal"><a href="/privacy">Privacy</a> &nbsp;·&nbsp; <a href="/terms">Terms</a></p>
                </footer>
              </>
            )}

          </div>
        </section>
      </main>

      {/* ── Confirmation dialog ── */}
      {showConfirmDlg && (
        <div className="contact-overlay" role="dialog" aria-modal="true"
          aria-label="Confirm password change" onClick={() => setShowConfirmDlg(false)}>
          <div className="contact-modal" onClick={e => e.stopPropagation()}>
            <button className="contact-close" aria-label="Close" onClick={() => setShowConfirmDlg(false)}>
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 3l10 10M13 3L3 13" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              </svg>
            </button>
            <div className="contact-eyebrow">Confirm change</div>
            <h2 className="contact-title">Change your password?</h2>
            <p className="contact-body">
              This will update the password for <strong>{email}</strong> and sign you out of all active sessions.
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <button
                type="button"
                className={`btn-primary${isLoading ? ' loading' : ''}`}
                disabled={isLoading}
                style={{ marginBottom: 0, justifyContent: 'center' }}
                onClick={() => pwForm.handleSubmit(onSubmitPassword)()}
              >
                {isLoading
                  ? <><Loader2 size={15} className="spinner-icon" /><span className="btn-primary-text">Updating…</span></>
                  : <><KeyRound size={16} /><span className="btn-primary-text" style={{ marginLeft: 8 }}>Yes, change password</span></>
                }
              </button>
              <button type="button" className="link-back" style={{ marginTop: 0 }}
                onClick={() => setShowConfirmDlg(false)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
