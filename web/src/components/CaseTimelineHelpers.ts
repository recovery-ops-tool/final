import type { ComponentType } from 'react';
import {
  AlertCircle, AlertTriangle, CheckCircle2, ClipboardCheck, Clock, Coins,
  FileDown, FileText, HandCoins, Handshake, History, Landmark,
  MailWarning, MapPin, MessageSquare, MessageSquareWarning, Pencil, Phone, RefreshCw, Repeat, ShieldAlert, ShieldOff,
  Table as TableIcon, UserCheck, UserPlus, UserX, XCircle,
} from 'lucide-react';
import type { CaseEventType } from '../types';

export type EventStyle = {
  icon: ComponentType<{ size?: number; className?: string }>;
  accent: string;
  label: string;
};

export const STYLES: Record<CaseEventType, EventStyle> = {
  ALLOCATION_CREATED:        { icon: FileText,          accent: 'var(--ink-secondary)',    label: 'Loan created' },
  ALLOCATION_STATUS_CHANGED: { icon: RefreshCw,         accent: 'var(--ink-secondary)',    label: 'Status changed' },
  ALLOCATION_DISPOSITION_CHANGED: { icon: Repeat,       accent: 'var(--ink-secondary)',    label: 'Disposition changed' },
  ALLOCATION_CLOSED:         { icon: CheckCircle2,      accent: 'var(--success, #16a34a)', label: 'Closed' },
  NPA_FLAG_RAISED:           { icon: ShieldAlert,       accent: 'var(--error, #dc2626)',   label: 'NPA flagged' },
  COOLING_OFF_STARTED:       { icon: ShieldOff,         accent: '#a16207',                 label: 'Cooling-off' },

  ASSIGNMENT_CREATED:        { icon: UserPlus,          accent: '#2563eb',                 label: 'Assigned' },
  ASSIGNMENT_REASSIGNED:     { icon: UserCheck,         accent: '#2563eb',                 label: 'Reassigned' },
  ASSIGNMENT_CANCELLED:      { icon: UserX,             accent: 'var(--ink-tertiary)',     label: 'Unassigned' },
  ASSIGNMENT_COMPLETED:      { icon: ClipboardCheck,    accent: '#2563eb',                 label: 'Done' },

  VISIT_LOGGED:              { icon: MapPin,            accent: 'var(--success)',  label: 'Visit' },
  VISIT_APPROVED:            { icon: CheckCircle2,      accent: 'var(--success)',  label: 'Visit OK' },
  VISIT_REJECTED:            { icon: XCircle,           accent: 'var(--error, #dc2626)',   label: 'Visit rejected' },

  CALL_MADE:                 { icon: Phone,             accent: '#0d9488',                 label: 'Call' },

  PTP_CREATED:               { icon: HandCoins,         accent: '#7c3aed',                 label: 'PTP' },
  PTP_FULFILLED:             { icon: CheckCircle2,      accent: '#7c3aed',                 label: 'PTP kept' },
  PTP_PARTIALLY_FULFILLED:   { icon: HandCoins,         accent: '#7c3aed',                 label: 'PTP partial' },
  PTP_BROKEN:                { icon: AlertTriangle,     accent: 'var(--error, #dc2626)',   label: 'PTP broken' },
  PTP_CANCELLED:             { icon: XCircle,           accent: 'var(--ink-tertiary)',     label: 'PTP cancelled' },
  PTP_RESCHEDULED:           { icon: Clock,             accent: '#a16207',                 label: 'PTP moved' },

  COLLECTION_SUBMITTED:      { icon: Coins,             accent: '#0891b2',                 label: 'Collection' },
  COLLECTION_APPROVED:       { icon: CheckCircle2,      accent: '#0891b2',                 label: 'Approved' },
  COLLECTION_REJECTED:       { icon: XCircle,           accent: 'var(--error, #dc2626)',   label: 'Rejected' },
  COLLECTION_DEPOSITED:      { icon: Landmark,          accent: '#0891b2',                 label: 'Deposited' },
  COLLECTION_CANCELLED:      { icon: XCircle,           accent: 'var(--ink-tertiary)',     label: 'Collection cancelled' },

  COMMUNICATION_SENT:        { icon: MessageSquare,     accent: 'var(--ink-secondary)',    label: 'Message' },
  COMMUNICATION_SUPPRESSED:  { icon: MailWarning,       accent: '#a16207',                 label: 'Suppressed' },
  COMMUNICATION_FAILED:      { icon: AlertCircle,       accent: 'var(--error, #dc2626)',   label: 'Failed' },

  RESTRUCTURE_PROPOSED:      { icon: Pencil,            accent: '#a16207',                 label: 'Restructure' },
  RESTRUCTURE_APPROVED:      { icon: CheckCircle2,      accent: '#a16207',                 label: 'Restructure OK' },
  RESTRUCTURE_REJECTED:      { icon: XCircle,           accent: 'var(--error, #dc2626)',   label: 'Restructure no' },
  SETTLEMENT_OFFERED:        { icon: Handshake,         accent: '#a16207',                 label: 'Settlement' },
  SETTLEMENT_ACCEPTED:       { icon: CheckCircle2,      accent: '#a16207',                 label: 'Settled' },
  SETTLEMENT_DECLINED:       { icon: XCircle,           accent: 'var(--ink-tertiary)',     label: 'Declined' },

  GRIEVANCE_RAISED:          { icon: MessageSquareWarning, accent: '#be185d',              label: 'Grievance' },
  GRIEVANCE_RESOLVED:        { icon: CheckCircle2,      accent: '#be185d',                 label: 'Grievance resolved' },
};

export const FALLBACK_STYLE: EventStyle = { icon: History, accent: 'var(--ink-tertiary)', label: 'Event' };
export const styleFor = (t: CaseEventType): EventStyle => STYLES[t] ?? FALLBACK_STYLE;

export const UUID_FIELD_KEY = /(Id|Ids|ID|UUID|Uuid|uuid)$/;
export const UUID_VALUE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export const fmtDateTime = (s?: string | null) =>
  s ? new Date(s).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

export const fmtDate = (s?: string | null) =>
  s ? new Date(s).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', year: 'numeric' }) : '—';

export const fmtCellValue = (v: unknown): string => {
  if (v == null) return '—';
  if (typeof v === 'number') return v.toLocaleString('en-IN');
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  const str = String(v);
  if (/^\d{4}-\d{2}-\d{2}T/.test(str)) return fmtDateTime(str);
  if (/^\d{4}-\d{2}-\d{2}$/.test(str)) return fmtDate(str);
  return str;
};

export const dayLabel = (ts: string): string => {
  const IST = 'Asia/Kolkata';
  const ymd = (x: Date) => x.toLocaleDateString('en-CA', { timeZone: IST }); // YYYY-MM-DD, sortable
  const d = new Date(ts);
  const today = new Date();
  const diff = Math.round((Date.parse(ymd(today)) - Date.parse(ymd(d))) / (1000 * 60 * 60 * 24));
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  if (diff > 1 && diff < 7) return d.toLocaleDateString('en-IN', { timeZone: IST, weekday: 'long' });
  return d.toLocaleDateString('en-IN', { timeZone: IST, day: 'numeric', month: 'short', year: 'numeric' });
};

export const CASE_EXPORT_OPTIONS: Array<{ include: string; label: string }> = [
  { include: 'all',             label: 'Full case history' },
  { include: 'visits',          label: 'Field visits only' },
  { include: 'collections',     label: 'Collections only' },
  { include: 'ptps',            label: 'Promises to pay only' },
  { include: 'communications',  label: 'Communications only' },
];

export { FileDown, FileText, TableIcon };
