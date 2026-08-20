import { useRef, useEffect } from 'react';
import { ChevronDown, User } from 'lucide-react';

interface Props {
  user: { firstName?: string; lastName?: string; email?: string };
  roleLabel: string;
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  avatarColor: string;
}

export default function AvatarDropdown({ user, roleLabel, open, onToggle, onClose, avatarColor }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const initials = ((user.firstName?.[0] ?? '').toUpperCase() + (user.lastName?.[0] ?? '').toUpperCase());
  const fullName = `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim() || user.email || 'User';

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, onClose]);

  return (
    <div className="avd-wrap" ref={wrapRef}>
      <button
        type="button"
        className="avd-trigger"
        onClick={onToggle}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={`User menu — ${fullName}`}
      >
        <span className="avd-avatar" style={{ background: avatarColor }}>{initials || <User size={12} />}</span>
        <ChevronDown size={11} className={`avd-chev${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>

      {open && (
        <div className="avd-panel" role="menu">
          <div className="avd-header">
            <span className="avd-header-avatar" style={{ background: avatarColor }}>{initials || <User size={14} />}</span>
            <div className="avd-header-info">
              <span className="avd-name">{fullName}</span>
              <span className="avd-email">{user.email}</span>
              <span className="avd-role">{roleLabel}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
