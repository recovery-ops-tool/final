import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle, ChevronDown, Download, GraduationCap, History, Printer, RefreshCw,
} from 'lucide-react';
import { apiClient, unwrapApiResponse } from '../client';
import type { CaseEvent, CaseTimelineResponse } from '../types';
import { dayLabel, CASE_EXPORT_OPTIONS, FileDown, FileText, TableIcon } from './CaseTimelineHelpers';
import { EventRow } from './CaseTimelineEventRow';
import './CaseTimeline.css';

interface Props { allocationId: string; }

export default function CaseTimeline({ allocationId }: Props) {
  const [data,           setData]           = useState<CaseTimelineResponse | null>(null);
  const [loading,        setLoading]        = useState(true);
  const [error,          setError]          = useState<string | null>(null);
  const [exporting,      setExporting]      = useState<false | 'raw' | 'sft' | 'case'>(false);
  const [caseExportOpen, setCaseExportOpen] = useState(false);
  const caseExportRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!caseExportOpen) return;
    const onDown = (e: MouseEvent) => { if (!caseExportRef.current?.contains(e.target as Node)) setCaseExportOpen(false); };
    const onKey  = (e: KeyboardEvent) => { if (e.key === 'Escape') setCaseExportOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [caseExportOpen]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError(null);
    
    Promise.all([
      apiClient.get(`/api/v1/cases/${allocationId}/timeline`).catch(() => ({ data: { data: { events: [] } } })),
      apiClient.get(`/api/v1/visit-logs/allocation/${allocationId}`).catch(() => ({ data: { data: [] } })),
      apiClient.get(`/api/v1/ptps/allocation/${allocationId}`).catch(() => ({ data: { data: [] } })),
      apiClient.get(`/api/v1/call-logs/allocation/${allocationId}`).catch(() => ({ data: { data: [] } }))
    ])
    .then(([timelineRes, visitsRes, ptpsRes, callsRes]) => {
      if (cancelled) return;
      const timelineData = (unwrapApiResponse<CaseTimelineResponse>(timelineRes.data) || { events: [] }) as CaseTimelineResponse;
      const visits = unwrapApiResponse<any[]>(visitsRes.data) || [];
      const ptps = unwrapApiResponse<any[]>(ptpsRes.data) || [];
      const calls = unwrapApiResponse<any[]>(callsRes.data) || [];
      
      const newEvents = [...(timelineData.events || [])];
      
      // Inject visits
      for (const v of visits) {
        newEvents.push({
          timestamp: v.visitDate || v.createdAt,
          eventType: 'VISIT_LOGGED',
          summary: `Visit logged by ${v.agentName || 'Agent'}`,
          actorName: v.agentName,
          sourceTable: 'VISIT_LOG',
          sourceId: v.id,
          data: { status: v.visitStatus, outcome: v.visitOutcome }
        });
      }
      
      // Inject PTPs
      for (const p of ptps) {
        newEvents.push({
          timestamp: p.createdAt,
          eventType: 'PTP_CREATED',
          summary: `PTP for ₹${p.promisedAmount} by ${p.agentName || 'Agent'}`,
          actorName: p.agentName,
          sourceTable: 'PTP',
          sourceId: p.id,
          data: { amount: p.promisedAmount, date: p.promisedDate }
        });
      }

      // Inject calls
      for (const c of calls) {
        const outcomeLabel = c.outcome ? String(c.outcome).replace(/_/g, ' ').toLowerCase() : 'no outcome recorded';
        newEvents.push({
          timestamp: c.initiatedAt || c.createdAt,
          eventType: 'CALL_MADE',
          summary: `Call by ${c.agentName || 'Agent'} — ${outcomeLabel}`,
          actorName: c.agentName,
          sourceTable: 'CALL_LOG',
          sourceId: c.id,
          narrative: c.notes,
          data: { outcome: c.outcome, durationSeconds: c.durationSeconds, phone: c.phoneMasked }
        });
      }

      // Sort descending
      timelineData.events = newEvents.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
      setData(timelineData);
    })
    .catch((e: any) => { if (!cancelled) setError(e?.response?.data?.message ?? e?.message ?? 'Failed to load timeline'); })
    .finally(() => { if (!cancelled) setLoading(false); });
    
    return () => { cancelled = true; };
  }, [allocationId]);

  const grouped = useMemo(() => {
    if (!data?.events) return [];
    const out: Array<{ key: string; label: string; events: CaseEvent[] }> = [];
    let current: { key: string; label: string; events: CaseEvent[] } | null = null;
    for (const ev of data.events) {
      const d = new Date(ev.timestamp);
      const key = d.toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' }); // YYYY-MM-DD, IST-anchored
      if (!current || current.key !== key) { current = { key, label: dayLabel(ev.timestamp), events: [] }; out.push(current); }
      current.events.push(ev);
    }
    return out;
  }, [data]);

  const slug = data?.loanNumber ? data.loanNumber.replace(/[^A-Za-z0-9._-]/g, '_') : allocationId;

  const triggerDownload = (blob: Blob, filename: string) => {
    const url = URL.createObjectURL(blob);
    const a = Object.assign(document.createElement('a'), { href: url, download: filename });
    document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
  };

  const download = async (kind: 'raw' | 'sft', path: string, prefix: string) => {
    setExporting(kind);
    try {
      const resp = await apiClient.get(`/api/v1/cases/${allocationId}/${path}`, { responseType: 'blob' });
      triggerDownload(new Blob([resp.data], { type: 'application/x-ndjson' }), `${prefix}-${slug}.jsonl`);
    } catch (e: any) { setError(e?.response?.data?.message ?? 'Export failed'); }
    finally { setExporting(false); }
  };

  const downloadCaseExport = async (include: string, format: 'pdf' | 'xlsx') => {
    setExporting('case'); setCaseExportOpen(false);
    try {
      const resp = await apiClient.get(`/api/v1/cases/${allocationId}/export`, { params: { include, format }, responseType: 'blob' });
      const mime = format === 'xlsx' ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' : 'application/pdf';
      const tag = include === 'all' ? 'full' : include.replace(/,/g, '+');
      triggerDownload(new Blob([resp.data], { type: mime }), `case-${slug}-${tag}.${format}`);
    } catch (e: any) { setError(e?.response?.data?.message ?? 'Case export failed'); }
    finally { setExporting(false); }
  };

  const exportDisabled = !!exporting || loading || !data || !data.events?.length;

  return (
    <div style={{ display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px 24px', borderBottom: '1px solid var(--border-subtle)' }}>
        <div style={{ fontSize: 13, color: 'var(--ink-secondary)', fontWeight: 600 }}>
          {data ? `${data.events.length} event${data.events.length === 1 ? '' : 's'} recorded` : 'Loading events...'}
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {/* Export buttons hidden due to missing backend endpoints (BCR-1) */}
        </div>
      </div>

      <div style={{ padding: '24px 32px' }}>
        {error && <div className="ds-banner is-error"><AlertCircle size={14} /><span>{error}</span></div>}
        {loading ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {Array.from({ length: 4 }).map((_, i) => <div key={i} className="ds-skel" style={{ height: 64, borderRadius: 8 }} />)}
          </div>
        ) : data && data.events.length === 0 ? (
          <p style={{ color: 'var(--ink-secondary)', textAlign: 'center', padding: '40px 0' }}>No activity recorded against this case yet.</p>
        ) : data ? (
          <ol className="case-timeline">
            {grouped.map((bucket) => (
              <li key={bucket.key} className="case-timeline-bucket">
                <div className="case-timeline-bucket-label" style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink-tertiary)', letterSpacing: '0.05em', marginBottom: 16 }}>
                  {bucket.label}
                </div>
                <ul className="case-timeline-list" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                  {bucket.events.map((ev) => <EventRow key={`${ev.sourceTable}-${ev.sourceId}-${ev.eventType}`} ev={ev} />)}
                </ul>
              </li>
            ))}
          </ol>
        ) : null}
      </div>
    </div>
  );
}

export { Download as TimelineDownloadIcon };
