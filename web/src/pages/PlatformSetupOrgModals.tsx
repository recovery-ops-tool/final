import { useState } from 'react';
import {
  platformApi,
  type OrganizationSummary,
  type CreateOrganizationRequest,
  type UpdateOrganizationRequest,
} from '../api/platformApi';
import { toastBus } from '../utils/toastBus';
import { AlertCircle, Trash2, Loader2 } from 'lucide-react';
import { Modal, ModalFooter, FormSection, Input } from './PlatformSetupShared';

export function CreateOrgModal({ onClose, onCreated }: { onClose: () => void; onCreated: (org: OrganizationSummary) => void }) {
  const [form, setForm] = useState<CreateOrganizationRequest>({
    name: '', code: '', adminEmail: '', adminFirstName: '', adminLastName: '',
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErr, setFieldErr] = useState<Partial<Record<keyof typeof form, string>>>({});

  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) => setForm(p => ({ ...p, [k]: v }));

  const validate = (): boolean => {
    const e: typeof fieldErr = {};
    if (!form.name.trim()) e.name = 'Required';
    if (!/^[A-Z0-9_-]+$/.test(form.code.trim())) e.code = 'Uppercase letters, digits, underscore or hyphen';
    if (!form.adminFirstName.trim()) e.adminFirstName = 'Required';
    if (!form.adminLastName.trim()) e.adminLastName = 'Required';
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.adminEmail.trim())) e.adminEmail = 'Invalid email';
    setFieldErr(e); return Object.keys(e).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    setSubmitting(true); setError(null);
    try {
      const org = await platformApi.createOrganization({
        ...form, code: form.code.toUpperCase(), adminEmail: form.adminEmail.toLowerCase().trim(),
      });
      toastBus.success('Organization created', `${org.name} is ready. A welcome email with sign-in instructions was sent to the admin.`);
      onCreated(org);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to create organization');
    } finally { setSubmitting(false); }
  };

  return (
    <Modal title="New organization" subtitle="Creates the tenant and its Org Admin in one step." onClose={onClose}>
      <FormSection title="Organization">
        <Input label="Name" value={form.name} onChange={v => set('name', v)} error={fieldErr.name} placeholder="ORG" required />
        <Input label="Code" value={form.code} onChange={v => set('code', v.toUpperCase())} error={fieldErr.code} placeholder="CODE" hint="Uppercase, no spaces." required />
      </FormSection>
      <FormSection title="Admin">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <Input label="First name" value={form.adminFirstName} onChange={v => set('adminFirstName', v)} error={fieldErr.adminFirstName} required />
          <Input label="Last name" value={form.adminLastName} onChange={v => set('adminLastName', v)} error={fieldErr.adminLastName} required />
        </div>
        <Input label="Email" type="email" value={form.adminEmail} onChange={v => set('adminEmail', v)} error={fieldErr.adminEmail} placeholder="admin@org.com" hint="They'll receive a welcome email with a one-time code to set their own password." required />
      </FormSection>
      {error && (
        <div className="ps-banner is-error" style={{ margin: 0 }}>
          <AlertCircle size={14} /><span className="ps-banner-content">{error}</span>
        </div>
      )}
      <ModalFooter onClose={onClose} submitting={submitting} onSubmit={submit} label="Create organization" />
    </Modal>
  );
}

export function AssignAdminModal({ org, onClose, onAssigned }: {
  org: OrganizationSummary; onClose: () => void; onAssigned: (updated: OrganizationSummary) => void;
}) {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '' });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({});

  const set = (k: string, v: string) => setForm(p => ({ ...p, [k]: v }));

  const validate = (): boolean => {
    const e: Record<string, string> = {};
    if (!form.firstName.trim()) e.firstName = 'Required';
    if (!form.lastName.trim())  e.lastName  = 'Required';
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.email.trim())) e.email = 'Invalid email';
    setFieldErr(e);
    return Object.keys(e).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    setSubmitting(true); setError(null);
    try {
      const user = await platformApi.createAdminUser({
        firstName:      form.firstName.trim(),
        lastName:       form.lastName.trim(),
        email:          form.email.toLowerCase().trim(),
        role:           'ORG_ADMIN',
        organizationId: org.id,
      });
      toastBus.success('Admin assigned', `${user.firstName} ${user.lastName} is now admin for ${org.name}.`);
      onAssigned({ ...org, orgAdminEmail: user.email });
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to assign admin');
    } finally { setSubmitting(false); }
  };

  return (
    <Modal title="Assign org admin" subtitle={`New admin account for ${org.name} (${org.code}). A welcome email with OTP is sent automatically.`} onClose={onClose}>
      <FormSection title="Admin details">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <Input label="First name" value={form.firstName} onChange={v => set('firstName', v)} error={fieldErr.firstName} required />
          <Input label="Last name"  value={form.lastName}  onChange={v => set('lastName', v)}  error={fieldErr.lastName}  required />
        </div>
        <Input label="Email" type="email" value={form.email} onChange={v => set('email', v)} error={fieldErr.email} placeholder="admin@org.com" required />
      </FormSection>
      {error && (
        <div className="ps-banner is-error" style={{ margin: 0 }}>
          <AlertCircle size={14} /><span className="ps-banner-content">{error}</span>
        </div>
      )}
      <ModalFooter onClose={onClose} submitting={submitting} onSubmit={submit} label="Create & assign admin" />
    </Modal>
  );
}

export function EditOrgModal({ org, onClose, onUpdated }: {
  org: OrganizationSummary; onClose: () => void; onUpdated: (updated: OrganizationSummary) => void;
}) {
  const [orgForm, setOrgForm] = useState<UpdateOrganizationRequest>({ name: org.name, code: org.code });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({});

  const setOrg = (k: keyof UpdateOrganizationRequest, v: string) => setOrgForm(p => ({ ...p, [k]: v }));

  const validate = (): boolean => {
    const e: Record<string, string> = {};
    if (!orgForm.name.trim()) e.name = 'Required';
    if (!/^[A-Z0-9_-]+$/.test(orgForm.code.trim())) e.code = 'Uppercase letters, digits, underscore or hyphen only';
    setFieldErr(e); return Object.keys(e).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    setSubmitting(true); setError(null);
    try {
      const updated = await platformApi.updateOrganization(org.id, {
        name: orgForm.name.trim(), code: orgForm.code.trim().toUpperCase(),
      });
      onUpdated(updated);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to update organization');
    } finally { setSubmitting(false); }
  };

  return (
    <Modal title="Edit organization" subtitle={`Editing: ${org.name}`} onClose={onClose}>
      <FormSection title="Organization">
        <Input label="Name" value={orgForm.name} onChange={v => setOrg('name', v)} error={fieldErr.name} required />
        <Input label="Code" value={orgForm.code} onChange={v => setOrg('code', v.toUpperCase())} error={fieldErr.code} hint="Uppercase, no spaces." required />
      </FormSection>
      {error && (
        <div className="ps-banner is-error" style={{ margin: 0 }}>
          <AlertCircle size={14} /><span className="ps-banner-content">{error}</span>
        </div>
      )}
      <ModalFooter onClose={onClose} submitting={submitting} onSubmit={submit} label="Save changes" />
    </Modal>
  );
}

export function DeleteOrgModal({ org, onClose, onDeleted }: {
  org: OrganizationSummary; onClose: () => void; onDeleted: (id: string) => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const confirm = async () => {
    setSubmitting(true); setError(null);
    try {
      await platformApi.deleteOrganization(org.id);
      onDeleted(org.id);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to delete organization');
      setSubmitting(false);
    }
  };

  return (
    <Modal title="Delete organization" onClose={onClose}>
      <div className="ps-banner is-error" style={{ margin: 0, alignItems: 'flex-start' }}>
        <Trash2 size={14} style={{ flexShrink: 0, marginTop: 2 }} />
        <span className="ps-banner-content">
          <strong>This action cannot be undone.</strong><br />
          Organization <strong>{org.name}</strong> ({org.code}) and all its data will be permanently deleted.
        </span>
      </div>
      {org.userCount > 0 && (
        <div className="ps-banner is-warn" style={{ margin: 0, marginTop: 10 }}>
          <AlertCircle size={14} />
          <span className="ps-banner-content">
            This organization has <strong>{org.userCount}</strong> user(s). Remove all users before deleting.
          </span>
        </div>
      )}
      {error && (
        <div className="ps-banner is-error" style={{ margin: 0, marginTop: 10 }}>
          <AlertCircle size={14} /><span className="ps-banner-content">{error}</span>
        </div>
      )}
      <div className="ds-modal-actions">
        <button type="button" onClick={onClose} disabled={submitting}
          className="ds-btn is-secondary" style={{ flex: 1 }}>Cancel</button>
        <button type="button" onClick={confirm} disabled={submitting || org.userCount > 0}
          className="ds-btn is-danger" style={{ flex: 1, justifyContent: 'center' }}>
          {submitting ? <Loader2 size={14} className="ds-spin" /> : <Trash2 size={14} />}
          Delete organization
        </button>
      </div>
    </Modal>
  );
}
