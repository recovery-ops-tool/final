import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import type { CSSProperties, MouseEvent as RMouseEvent } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
// Submenu icons now come from navConfig via item.children, so the sidebar no
// longer imports one icon per hardcoded sub-link.
import { Lock, LogOut, PanelLeft, ChevronDown, User } from 'lucide-react';
import { Logo } from './Logo';
import { prefetchRoute } from '../utils/routePrefetch';
import { useT } from '../utils/i18n';
import {
  type AppSidebarProps,
} from './AppSidebarHelpers';
import { LogoutConfirmDialog } from '../pages/LogoutConfirmDialog';
import './AppSidebar.css';

export type { SidebarNavItem, SidebarSection, AppSidebarProps } from './AppSidebarHelpers';

interface TipState { label: string; x: number; y: number }

function useRipple<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const fire = useCallback((e: React.MouseEvent) => {
    const el = ref.current; if (!el) return;
    const rect = el.getBoundingClientRect();
    const size = Math.max(rect.width, rect.height) * 2.2;
    const span = document.createElement('span');
    span.className = 'popup-ripple';
    span.style.cssText = `width:${size}px;height:${size}px;left:${e.clientX - rect.left - size/2}px;top:${e.clientY - rect.top - size/2}px`;
    el.appendChild(span);
    span.addEventListener('animationend', () => span.remove(), { once: true });
  }, []);
  return { ref, fire };
}

function initials(user?: { firstName?: string; lastName?: string; email?: string } | null) {
  if (user?.firstName && user?.lastName) return (user.firstName[0] + user.lastName[0]).toUpperCase();
  if (user?.firstName) return user.firstName.slice(0, 2).toUpperCase();
  if (user?.email) return user.email[0].toUpperCase();
  return null;
}

export default function AppSidebar({
  sections = [],
  collapsed = false,
  onToggleCollapse,
  onProfileSettings,
  onNotificationsClick,
  unreadCount = 0,
  onLogout,
  user,
  roleLabel,
  avatarColor = 'var(--brand)',
}: AppSidebarProps) {
  const location = useLocation();
  const t = useT();
  const expanded = !collapsed;

  const [confirmLogout,       setConfirmLogout]       = useState(false);
  const [isLoggingOut,        setIsLoggingOut]        = useState(false);
  const [pendingTo,           setPendingTo]           = useState<string | null>(null);
  const [scrollMap,           setScrollMap]           = useState({ visible: false, top: 0, height: 0 });
  const [tip,                 setTip]                 = useState<TipState | null>(null);

  /* Submenu expansion, keyed by parent label. This replaced five separate
     useState/useEffect pairs and five hardcoded <ul> blocks — every parent
     with `children` in navConfig now gets a chevron for free, so adding a
     page to the sidebar is a data edit, not a component edit. */
  // Only records labels the user has explicitly toggled. Whether a group is
  // open is derived below, not synced in an effect — the old code kept five
  // booleans in state and five effects writing to them on every navigation.
  const [toggledGroups, setToggledGroups] = useState<Record<string, boolean>>({});

  const toggleGroup = (label: string) =>
    setToggledGroups(prev => ({ ...prev, [label]: !isGroupOpen(label) }));

  /** Closed by default, regardless of the active route — a submenu only opens
   *  once the user explicitly clicks the parent or its chevron. */
  const isGroupOpen = (label: string): boolean => toggledGroups[label] ?? false;

  const rippleConfirm = useRipple<HTMLButtonElement>();
  const rippleCancel  = useRipple<HTMLButtonElement>();

  const navRef          = useRef<HTMLElement>(null);

  useEffect(() => {
    if (pendingTo === null) return;
    if (location.pathname === pendingTo || (pendingTo !== '/' && location.pathname.startsWith(pendingTo + '/'))) setPendingTo(null);
  }, [location.pathname, pendingTo]);

  useEffect(() => {
    const nav = navRef.current;
    if (!nav) return;
    const update = () => {
      const { scrollTop, scrollHeight, clientHeight, offsetTop } = nav;
      if (scrollHeight <= clientHeight + 4) { setScrollMap({ visible: false, top: 0, height: 0 }); return; }
      // Compact indicator: use a smaller multiplier and lower min-height
      const heightPx = Math.max(12, (clientHeight / scrollHeight) * (clientHeight * 0.4));
      const range = clientHeight - heightPx;
      const topPx = offsetTop + (scrollTop / (scrollHeight - clientHeight)) * range;
      setScrollMap({ visible: true, top: topPx, height: heightPx });
    };
    update();
    nav.addEventListener('scroll', update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(nav);
    return () => { nav.removeEventListener('scroll', update); ro.disconnect(); };
  }, []);

  const activePath = useMemo<string | null>(() => {
    let best: string | null = null;
    for (const section of sections)
      for (const item of section.items) {
        const to = item.to;
        if (to.includes('?')) continue;
        if (location.pathname === to || (to !== '/' && location.pathname.startsWith(to + '/')))
          if (best === null || to.length > best.length) best = to;
      }
    return best;
  }, [sections, location.pathname]);

  const computeActive = (to: string) => pendingTo !== null ? pendingTo === to : to === activePath;

  // Submenu items are matched on pathname AND query string — several platform-
  // admin submenus (Platform Setup, Feature Flags, Lucien) share one pathname
  // across tabs (e.g. /platform/feature-flags vs ?tab=overrides), so matching
  // on pathname alone made every sibling light up active at once.
  const computeChildActive = (to: string) => {
    const [path, query = ''] = to.split('?');
    if (location.pathname !== path) return false;
    return location.search.replace(/^\?/, '') === query;
  };

  const showTip = useCallback((e: RMouseEvent<HTMLElement>, label: string) => {
    const r = e.currentTarget.getBoundingClientRect();
    setTip({ label, x: r.right + 10, y: r.top + r.height / 2 });
  }, []);
  const hideTip = useCallback(() => setTip(null), []);
  useEffect(() => { if (expanded) setTip(null); }, [expanded]);

  // First name only — the surname added nothing the avatar and role line don't
  // already carry, and it was the first thing to truncate in the footer pill.
  const displayName = user?.firstName || user?.email || 'Account';

  return (
    <aside
      className={`asb-root${collapsed ? ' is-collapsed' : ''}`}
      aria-label="Main sidebar"
    >
      <div className="asb-header">
        {expanded ? (
          <>
            <span className="asb-header-logo--btn" aria-label="Recoverpro">
              <Logo height={34} />
            </span>
            {onToggleCollapse && (
              <button
                type="button"
                className="asb-collapse-toggle"
                onClick={onToggleCollapse}
                aria-label="Collapse sidebar"
                title="Collapse sidebar"
              >
                <PanelLeft size={16} aria-hidden="true" />
              </button>
            )}
          </>
        ) : (
          onToggleCollapse && (
            <button
              type="button"
              className="asb-collapse-toggle"
              onClick={onToggleCollapse}
              aria-label="Expand sidebar"
              title="Expand sidebar"
            >
              <PanelLeft size={16} aria-hidden="true" />
            </button>
          )
        )}
      </div>
      {/* ── Org workspace anchor (Notion / Shopify pattern) ── */}
      {expanded && user?.organizationName && (
        <button type="button" className="asb-org-anchor" onClick={onProfileSettings} title="Organization settings">
          <span className="asb-org-anchor-dot" aria-hidden="true" />
          <span className="asb-org-anchor-name">{user.organizationName}</span>
        </button>
      )}

      {expanded && scrollMap.visible && (
        <span className="asb-scroll-map" aria-hidden="true"
          style={{ top: `${scrollMap.top}px`, height: `${scrollMap.height}px` }}
        />
      )}

      {/* ── Nav ── */}
      <nav className="asb-nav" aria-label="Primary navigation" ref={navRef}>
        {sections.map(section => {
          return (
            <div key={section.label} className="asb-section">

              {/* Section header — static label per spec. "Main" is the sidebar's
                  only-usually section, so it stays unlabeled; other sections
                  (e.g. Saved Views) still get their label for contrast. */}
              {expanded ? (
                section.label !== 'Main' && (
                  <div className="asb-section-header">
                    <span className="asb-section-label">{t(section.label)}</span>
                  </div>
                )
              ) : (
                <div className="asb-section-divider" aria-hidden="true" />
              )}

              {/* Collapsible items wrapper (no longer collapses via click, but DOM remains) */}
              <div className="asb-section-wrap">
                <ul role="list">
                  {section.items.map(item => {
                    const isActive = computeActive(item.to);
                    const label = t(item.label);
                    const kids = item.children ?? [];
                    const hasKids = kids.length > 0 && expanded;
                    const isParentExpanded = hasKids && isGroupOpen(item.label);

                    return (
                      <li key={item.to} style={{ position: 'relative' }}>
                        <NavLink
                          to={item.to}
                          className={`asb-nav-item${isActive ? ' is-active' : ''}`}
                          aria-current={isActive ? 'page' : undefined}
                          aria-label={collapsed ? label : undefined}
                          onClick={() => setPendingTo(item.to)}
                          onMouseEnter={(e) => { prefetchRoute(item.to); if (collapsed) showTip(e, label); }}
                          onMouseLeave={hideTip}
                          onFocus={() => prefetchRoute(item.to)}
                          style={hasKids ? { paddingRight: 40 } : undefined}
                        >
                           <span className="asb-nav-icon">
                             <item.icon size={14} aria-hidden="true" />
                            {!!item.badge && item.badge > 0 && (
                              <span className="asb-badge" aria-label={`${item.badge} new`}>{item.badge}</span>
                            )}
                           </span>
                           {expanded && (
                            <>
                              <span className="asb-nav-label">{label}</span>
                              {!!item.count && item.count > 0 && (
                                <span className="asb-nav-count" aria-label={`${item.count} pending`}>
                                  {item.count > 99 ? '99+' : item.count}
                                </span>
                              )}
                              {item.locked && (
                                <Lock size={10} style={{ marginLeft: 'auto', color: 'var(--text-tertiary)', opacity: 0.7 }} aria-label="Feature locked" />
                              )}
                            </>
                           )}
                        </NavLink>
                        {/* Chevron + submenu, driven entirely by item.children.
                            Was five hardcoded label checks and five <ul> blocks. */}
                        {hasKids && (
                          <button
                            type="button"
                            className="asb-chevron-btn"
                            aria-label={`${isParentExpanded ? 'Collapse' : 'Expand'} ${label}`}
                            aria-expanded={isParentExpanded}
                            onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggleGroup(item.label); }}
                          >
                            <ChevronDown size={14} style={{ transform: isParentExpanded ? 'rotate(180deg)' : 'none', transition: 'transform 0.2s' }} />
                          </button>
                        )}
                        {hasKids && isParentExpanded && (
                          <ul className="asb-submenu">
                            {kids.map(child => (
                              <li key={child.to}>
                                <NavLink
                                  to={child.to}
                                  end
                                  className={`asb-nav-item asb-sub-item${computeChildActive(child.to) ? ' is-active' : ''}`}
                                  onClick={() => setPendingTo(child.to)}
                                  onMouseEnter={() => prefetchRoute(child.to)}
                                  onFocus={() => prefetchRoute(child.to)}
                                >
                                  <span className="asb-nav-icon">
                                    <child.icon size={12} />
                                  </span>
                                  <span className="asb-nav-label">{t(child.label)}</span>
                                </NavLink>
                              </li>
                            ))}
                          </ul>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </div>
            </div>
          );
        })}
      </nav>

      {/* ── Identity footer ── */}
      <div className="asb-footer" aria-label="Account">
        {collapsed ? (
          <div className="asb-footer-rail-btn"
            aria-label={displayName}
            onMouseEnter={e => showTip(e, displayName)}
            onMouseLeave={hideTip}
          >
            <span className="asb-avatar" style={{ background: avatarColor }}>{initials(user) || <User size={14} />}</span>
          </div>
        ) : (
          <div className="asb-footer-pill">
            <span className="asb-avatar-wrap">
              <span className="asb-avatar" style={{ background: avatarColor }}>{initials(user) || <User size={14} />}</span>
            </span>
            <div className="asb-footer-identity">
              <span className="asb-footer-name" title={displayName}>{displayName}</span>
              <div className="asb-footer-meta-row">
                {roleLabel && <span className="asb-footer-role">{roleLabel}</span>}
                {roleLabel && user?.organizationName && <span className="asb-footer-sep" aria-hidden="true">·</span>}
                {user?.organizationName && (
                  <span className="asb-footer-org" title={user.organizationName}>{user.organizationName}</span>
                )}
              </div>
            </div>
            
            <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
              {onLogout && (
                <button type="button" className="asb-footer-logout-icon" style={{ marginLeft: 0 }}
                  onClick={e => { e.stopPropagation(); setConfirmLogout(true); }}
                  aria-label="Sign out" title="Sign out"
                >
                  <LogOut size={14} />
                </button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* ── Sign-out confirm popup ── */}
      {createPortal(
        <LogoutConfirmDialog
          open={confirmLogout}
          isLoggingOut={isLoggingOut}
          onCancel={() => setConfirmLogout(false)}
          onConfirm={() => {
            setIsLoggingOut(true);
            onLogout?.();
          }}
        />,
        document.body
      )}

      {/* Portaled to <body>: .asb-root sets `contain: paint` (for its own
          overflow: hidden clipping), which makes it the containing block for
          any `position: fixed` descendant too — so a tooltip left as a normal
          child gets clipped the moment it pokes past the 64px collapsed rail
          and never renders. Portaling escapes that containment entirely. */}
      {tip && createPortal(
        <div className="asb-tip" role="tooltip" style={{ left: `${tip.x}px`, top: `${tip.y}px` }}>
          {tip.label}
        </div>,
        document.body
      )}
    </aside>
  );
}
