import { useCallback, useEffect, useState } from 'react';
import { platformApi } from '../api/platformApi';
import type { OrganizationSummary } from '../api/platformApi';
import { usersApi } from '../api/usersApi';
import type { UserResponse } from '../types';
import { useAuth } from '../AuthContext';
import {
  Building2, AlertCircle, Users, Shield, ChevronDown, ToggleLeft, ToggleRight, SquarePen, Trash2, User,
} from 'lucide-react';
import { Modal, ModalFooter, FormSection, Input } from './PlatformSetupShared';
import { UsersEditModal } from './UsersEditModal';
import { DeleteUserModal } from './PlatformSetupUserModals';
import { toastBus } from '../utils/toastBus';

function CreateAdminUserModal({ orgs, onClose, onCreated }: {
  orgs: OrganizationSummary[];
  onClose: () => void;
  onCreated: (u: UserResponse) => void;
}) {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', role: 'ORG_ADMIN', organizationId: '' });
  const [submitting, setSubmitting] = useState(false);
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({});

  const set = (k: string, v: string) => setForm(p => ({ ...p, [k]: v }));

  const validate = (): boolean => {
    const e: Record<string, string> = {};
    if (!form.firstName.trim()) e.firstName = 'Required';
    if (!form.lastName.trim())  e.lastName  = 'Required';
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.email.trim())) e.email = 'Invalid email';
    if (form.role === 'ORG_ADMIN' && !form.organizationId) e.organizationId = 'Required for Org Admin';
    setFieldErr(e);
    return Object.keys(e).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    setSubmitting(true);
    try {
      const payload: Parameters<typeof platformApi.createAdminUser>[0] = {
        email:     form.email.toLowerCase().trim(),
        firstName: form.firstName.trim(),
        lastName:  form.lastName.trim(),
        role:      form.role,
      };
      if (form.role === 'ORG_ADMIN') payload.organizationId = form.organizationId;
      const user = await platformApi.createAdminUser(payload);
      toastBus.success('Admin user created', 'A welcome email with login instructions has been sent.');
      onCreated(user);
    } catch {
      // interceptor shows error toast
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal title="New admin user" subtitle="Provisions a platform or org admin account. A welcome email with OTP is sent automatically." onClose={onClose}>
      <FormSection title="User details">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <Input label="First name" value={form.firstName} onChange={v => set('firstName', v)} error={fieldErr.firstName} required />
          <Input label="Last name"  value={form.lastName}  onChange={v => set('lastName', v)}  error={fieldErr.lastName}  required />
        </div>
        <Input label="Email" type="email" value={form.email} onChange={v => set('email', v)} error={fieldErr.email} required />
      </FormSection>
      <FormSection title="Role & access">
        <div className="ds-field">
          <label className="ds-label">Role</label>
          <div className="ps-select-wrap">
            <select value={form.role} onChange={e => set('role', e.target.value)} className="ds-select" style={{ width: '100%' }}>
              <option value="ORG_ADMIN">Org Admin</option>
              <option value="PLATFORM_ADMIN">Platform Admin</option>
            </select>
            <ChevronDown size={13} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--ink-tertiary)', pointerEvents: 'none' }} />
          </div>
        </div>
        {form.role === 'ORG_ADMIN' && (
          <div className="ds-field">
            <label className="ds-label">Organization <span style={{ color: 'var(--error)' }}>*</span></label>
            <div className="ps-select-wrap">
              <select
                value={form.organizationId}
                onChange={e => set('organizationId', e.target.value)}
                className={`ds-select${fieldErr.organizationId ? ' is-error' : ''}`}
                style={{ width: '100%' }}
              >
                <option value="">Select organization…</option>
                {orgs.map(o => (
                  <option key={o.id} value={o.id}>{o.name} ({o.code})</option>
                ))}
              </select>
              <ChevronDown size={13} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--ink-tertiary)', pointerEvents: 'none' }} />
            </div>
            {fieldErr.organizationId && <p className="ds-field-error">{fieldErr.organizationId}</p>}
          </div>
        )}
      </FormSection>
      <ModalFooter onClose={onClose} submitting={submitting} onSubmit={submit} label="Create user" />
    </Modal>
  );
}

interface UsersTabProps {
  orgs: OrganizationSummary[];
  showCreate: boolean;
  setShowCreate: (v: boolean) => void;
}

export function UsersTab({ orgs, showCreate, setShowCreate }: UsersTabProps) {
  const { user: currentUser } = useAuth();
  const [users,       setUsers]       = useState<UserResponse[]>([]);
  const [loading,     setLoading]     = useState(true);
  const [error,       setError]       = useState<string | null>(null);
  const [editingUser, setEditingUser] = useState<UserResponse | null>(null);
  const [deletingUser, setDeletingUser] = useState<UserResponse | null>(null);

  const orgMap = Object.fromEntries(orgs.map(o => [o.id, o.name]));

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      setUsers(await platformApi.listAdminUsers());
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to load admin users');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const toggleEnabled = async (u: UserResponse) => {
    try {
      if (u.enabled) await usersApi.disableUser(u.id); else await usersApi.enableUser(u.id);
      setUsers(prev => prev.map(x => x.id === u.id ? { ...x, enabled: !u.enabled } : x));
    } catch { /* interceptor shows toast */ }
  };

  return (
    <>
      {error && (
        <div className="ps-banner is-error" style={{ marginBottom: 10 }}>
          <AlertCircle size={14} /><span className="ps-banner-content">{error}</span>
        </div>
      )}

      {/* ── Table ── */}
      <div className="ds-table-card">
        <div className="ds-table-wrap">
          <table className="ds-table is-no-row-hover ps-table">
            <thead>
              <tr>
                <th>Name</th><th>Email</th><th>Role</th><th>Organization</th><th>Status</th>
                <th className="is-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <tr key={i} style={{ opacity: 1 - i * 0.08 }}>
                    {Array.from({ length: 6 }).map((__, j) => (
                      <td key={j}><div className="ds-skel" style={{ height: 14, width: '75%' }} /></td>
                    ))}
                  </tr>
                ))
              ) : users.length === 0 ? (
                <tr>
                  <td colSpan={6}>
                    <div className="ds-empty">
                      <span className="ds-empty-icon"><Users size={20} /></span>
                      <span className="ds-empty-title">No admin users yet</span>
                      <span className="ds-empty-sub">Create the first admin account to get started.</span>
                    </div>
                  </td>
                </tr>
              ) : (
                users.map(u => {
                  const roleName    = u.roles?.[0]?.name?.replace('ROLE_', '') ?? '—';
                  const isPlatform  = roleName === 'PLATFORM_ADMIN';
                  const initials    = `${u.firstName?.[0] ?? ''}${u.lastName?.[0] ?? ''}`.toUpperCase();
                  const orgName     = u.organizationId ? (orgMap[u.organizationId] ?? u.organizationId) : null;
                  return (
                    <tr key={u.id}>
                      <td>
                        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
                          <span className="ps-avatar">{initials || <User size={14} />}</span>
                          <span style={{ fontWeight: 600, color: 'var(--ink-primary)' }}>{u.firstName} {u.lastName}</span>
                        </div>
                      </td>
                      <td className="is-mono is-muted">{u.email}</td>
                      <td>
                        <span className={`ds-pill ${isPlatform ? 'is-warn' : 'is-info'}`}>
                          {isPlatform ? <Shield size={10} /> : <Building2 size={10} />}
                          {roleName.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())}
                        </span>
                      </td>
                      <td style={{ fontSize: 12.5 }}>
                        {isPlatform
                          ? <span style={{ color: 'var(--ink-tertiary)', fontStyle: 'italic' }}>platform</span>
                          : orgName
                            ? <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: 'var(--ink-secondary)' }}>
                                <Building2 size={11} style={{ color: 'var(--ink-tertiary)' }} />{orgName}
                              </span>
                            : <span style={{ color: 'var(--ink-tertiary)' }}>—</span>}
                      </td>
                      <td>
                        <span className={`ds-pill ${u.enabled ? 'is-success' : 'is-neutral'}`}>
                          {u.enabled ? 'Active' : 'Disabled'}
                        </span>
                      </td>
                      <td className="is-right">
                        {(() => {
                          const isSelf = u.id === currentUser?.id;
                          return (
                            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2, justifyContent: 'flex-end' }}>
                              <button type="button" onClick={() => toggleEnabled(u)} disabled={isSelf}
                                className="ds-table-row-action"
                                title={isSelf ? "You can't deactivate your own account" : (u.enabled ? 'Deactivate' : 'Activate')}>
                                {u.enabled
                                  ? <ToggleRight size={16} style={{ color: 'var(--success)' }} />
                                  : <ToggleLeft  size={16} />}
                              </button>
                              <button type="button" onClick={() => setEditingUser(u)} className="ds-table-row-action" title="Edit">
                                <SquarePen size={14} />
                              </button>
                              <button type="button" onClick={() => setDeletingUser(u)} disabled={isSelf}
                                className="ds-table-row-action is-danger"
                                title={isSelf ? "You can't delete your own account" : 'Delete'}>
                                <Trash2 size={14} />
                              </button>
                            </div>
                          );
                        })()}
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {showCreate && (
        <CreateAdminUserModal
          orgs={orgs}
          onClose={() => setShowCreate(false)}
          onCreated={u => { setUsers(prev => [u, ...prev]); setShowCreate(false); }}
        />
      )}
      {editingUser && (
        <UsersEditModal
          user={editingUser}
          onClose={() => setEditingUser(null)}
          onSaved={() => { setEditingUser(null); load(); }}
        />
      )}
      {deletingUser && (
        <DeleteUserModal
          user={deletingUser}
          onClose={() => setDeletingUser(null)}
          onDeleted={id => { setUsers(prev => prev.filter(u => u.id !== id)); setDeletingUser(null); }}
        />
      )}
    </>
  );
}
