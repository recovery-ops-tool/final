import React, { useCallback, useState } from 'react';
import {
  View, TextInput, Pressable, FlatList,
  ScrollView, RefreshControl, Modal,
} from 'react-native';
import { useFocusEffect } from 'expo-router';
import {
  Search, WifiOff, X, FileText, ShieldCheck,
  User, Calendar, MapPin, Banknote, Filter,
} from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, EmptyState, LoadingView, Badge } from '@/components/ui';

import { auditApi, type UserActionAuditResponse, type AuditLogResponse } from '@/api/auditApi';
import { visitLogApi } from '@/api/visitLogApi';
import { collectionsApi } from '@/api/collectionsApi';
import { ptpsApi } from '@/api/ptpsApi';
import { useAuth } from '@/context/AuthContext';
import { formatDate } from '@/utils/date';
import type { VisitLogResponse, CollectionResponse, PtpResponse } from '@/types/domain';

// ─── Unified Event Type ───────────────────────────────────────────────────────

export type EventKind = 'assignment' | 'visit' | 'collection' | 'ptp';

export interface OrgEvent {
  key: string;
  kind: EventKind;
  title: string;
  subtitle?: string;
  actor?: string;
  ts: string;
  amount?: number;
  status?: string;
  allocationId?: string;
  reason?: string;
}

const KIND_META = {
  assignment: { label: 'Assignments', Icon: User },
  visit:      { label: 'Visits',      Icon: MapPin },
  collection: { label: 'Collections', Icon: Banknote },
  ptp:        { label: 'PTPs',        Icon: Calendar },
};

function fromAudit(l: AuditLogResponse): OrgEvent {
  const title = l.action === 'ASSIGNED' ? 'Case assigned' : l.action === 'REASSIGNED' ? 'Case reassigned'
    : l.action === 'CANCELLED' ? 'Assignment cancelled' : l.action === 'DELETED' ? 'Assignment deleted'
    : l.action.replace(/_/g, ' ');
  return { key: `al-${l.id}`, kind: 'assignment', title, actor: l.performedByName ?? l.userEmail, ts: l.createdAt, allocationId: l.allocationId, reason: l.reason, status: l.action };
}

function fromVisit(v: VisitLogResponse): OrgEvent {
  const disp = v.disp ?? v.visitOutcome;
  const subtitle = [disp, v.contactability?.replace(/_/g, ' ')].filter(Boolean).join(' · ');
  return { key: `vis-${v.id}`, kind: 'visit', title: 'Field visit', subtitle: subtitle || undefined, actor: v.agentName ?? undefined, ts: v.createdAt, amount: v.amountCollected ?? undefined, status: v.approvalStatus, allocationId: v.allocationId };
}

function fromCollection(c: CollectionResponse): OrgEvent {
  const title = c.status === 'APPROVED' ? 'Collection approved' : c.status === 'REJECTED' ? 'Collection rejected'
    : c.status === 'DEPOSITED' ? 'Collection deposited' : c.status === 'CANCELLED' ? 'Collection cancelled' : 'Collection submitted';
  return { key: `col-${c.id}`, kind: 'collection', title, subtitle: c.paymentMode?.replace(/_/g, ' '), ts: c.approvedAt ?? c.depositedAt ?? c.createdAt, amount: c.amount, status: c.status, allocationId: c.allocationId, reason: c.rejectionReason };
}

function fromPtp(p: PtpResponse): OrgEvent {
  const title = p.status === 'FULFILLED' ? 'PTP fulfilled' : p.status === 'BROKEN' ? 'PTP broken'
    : p.status === 'PARTIALLY_FULFILLED' ? 'PTP partial' : p.status === 'CANCELLED' ? 'PTP cancelled' : 'PTP recorded';
  return { key: `ptp-${p.id}`, kind: 'ptp', title, subtitle: p.borrowerName || undefined, actor: p.agentName ?? undefined, ts: p.fulfilledAt ?? p.brokenAt ?? p.updatedAt ?? p.createdAt, amount: p.promisedAmount, status: p.status, allocationId: p.allocationId, reason: p.brokenReason ?? p.cancellationReason };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

type Tab = 'activity' | 'admin';
type FilterKind = EventKind | 'all';

function statusTone(action: string): 'success' | 'error' | 'warning' | 'info' | 'neutral' {
  const a = action?.toUpperCase() ?? '';
  if (['ASSIGNED', 'APPROVED', 'DEPOSITED', 'FULFILLED', 'CREATED', 'ENABLED'].includes(a)) return 'success';
  if (['REJECTED', 'BROKEN', 'CANCELLED', 'DELETED', 'DISABLED'].includes(a)) return 'error';
  if (['PENDING', 'PARTIALLY_FULFILLED', 'PENDING_APPROVAL', 'UPDATED'].includes(a)) return 'warning';
  if (['REASSIGNED', 'TRANSFERRED'].includes(a)) return 'info';
  return 'neutral';
}

function fmtTime(ts: string): string {
  return new Date(ts).toLocaleTimeString('en-IN', {
    timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit',
  });
}

function fmtDateTime(ts: string): string {
  return new Date(ts).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short',
    year: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

function groupByDate(logs: OrgEvent[]): { dateLabel: string; items: OrgEvent[] }[] {
  const map: Record<string, OrgEvent[]> = {};
  for (const log of logs) {
    const d = new Date(log.ts);
    const key = isNaN(d.getTime())
      ? 'Unknown Date'
      : d.toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', year: 'numeric', month: 'long', day: 'numeric' });
    (map[key] ??= []).push(log);
  }
  return Object.entries(map).map(([dateLabel, items]) => ({ dateLabel, items }));
}

// ─── Activity Timeline ────────────────────────────────────────────────────────

interface ActivityFeedProps {
  logs: OrgEvent[];
  loading: boolean;
  refreshing: boolean;
  loadError: boolean;
  onRefresh: () => void;
  search: string;
  setSearch: (v: string) => void;
}

function ActivityFeed({
  logs, loading, refreshing, loadError, onRefresh, search, setSearch,
}: ActivityFeedProps) {
  const { colors, spacing, radius } = useTheme();

  // Filter States
  const [filter, setFilter] = useState<FilterKind>('all');
  const [fromDate, setFromDate] = useState<string>('');
  const [toDate, setToDate] = useState<string>('');
  const [modalOpen, setModalOpen] = useState(false);

  // Temp Modal States
  const [tempFilter, setTempFilter] = useState<FilterKind>('all');
  const [tempFromDate, setTempFromDate] = useState<string>('');
  const [tempToDate, setTempToDate] = useState<string>('');

  const openFilterModal = () => {
    setTempFilter(filter);
    setTempFromDate(fromDate);
    setTempToDate(toDate);
    setModalOpen(true);
  };

  const applyFilters = () => {
    setFilter(tempFilter);
    setFromDate(tempFromDate);
    setToDate(tempToDate);
    setModalOpen(false);
  };

  const clearAllFilters = () => {
    setFilter('all');
    setFromDate('');
    setToDate('');
    setTempFilter('all');
    setTempFromDate('');
    setTempToDate('');
    setModalOpen(false);
  };

  const filtered = logs.filter((l) => {
    // 1. Text Search
    const q = search.trim().toLowerCase();
    if (q) {
      const match = l.title?.toLowerCase().includes(q) ||
        l.subtitle?.toLowerCase().includes(q) ||
        l.actor?.toLowerCase().includes(q) ||
        l.status?.toLowerCase().includes(q);
      if (!match) return false;
    }

    // 2. Kind Filter
    if (filter !== 'all' && l.kind !== filter) return false;

    // 3. Date Filters
    if (fromDate) {
      const eDate = new Date(l.ts).getTime();
      const fDate = new Date(`${fromDate}T00:00:00+05:30`).getTime();
      if (eDate < fDate) return false;
    }
    if (toDate) {
      const eDate = new Date(l.ts).getTime();
      const tDate = new Date(`${toDate}T00:00:00+05:30`).getTime() + 86400000;
      if (eDate >= tDate) return false;
    }

    return true;
  });

  const grouped = groupByDate(filtered);

  if (loading) return <LoadingView label="Loading audit trail..." />;

  const FILTERS: { id: FilterKind; label: string }[] = [
    { id: 'all', label: 'All' },
    { id: 'assignment', label: 'Assignments' },
    { id: 'visit', label: 'Visits' },
    { id: 'collection', label: 'Collections' },
    { id: 'ptp', label: 'PTPs' },
  ];

  return (
    <ScrollView
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
      showsVerticalScrollIndicator={false}
      contentContainerStyle={{ gap: spacing.s4, paddingBottom: spacing.s8 ?? 40 }}
    >
      {/* Search & Filter */}
      <View style={{ flexDirection: 'row', gap: spacing.s2, alignItems: 'center' }}>
        <View style={{
          flex: 1,
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md,
          paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}>
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search by action or actor..."
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>

        <Pressable 
          style={{
            width: 44, height: 44,
            backgroundColor: (filter !== 'all' || fromDate || toDate) ? colors.accent : colors.surface,
            borderRadius: radius.md,
            borderWidth: 1, borderColor: (filter !== 'all' || fromDate || toDate) ? colors.accent : colors.border,
            alignItems: 'center', justifyContent: 'center'
          }}
          onPress={openFilterModal}
        >
          <Filter size={20} color={(filter !== 'all' || fromDate || toDate) ? '#fff' : colors.ink2} />
        </Pressable>
      </View>

      {/* Active Filter Pills */}
      {(filter !== 'all' || fromDate || toDate) && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: spacing.s2, paddingBottom: spacing.s1 }}>
          {filter !== 'all' && (
            <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: colors.accent, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12, gap: 4 }}>
              <Text style={{ fontSize: 12, color: '#fff' }}>{FILTERS.find(f => f.id === filter)?.label}</Text>
              <Pressable onPress={() => setFilter('all')} hitSlop={10}><X size={12} color="#fff" /></Pressable>
            </View>
          )}
          {fromDate !== '' && (
            <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: colors.accent, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12, gap: 4 }}>
              <Text style={{ fontSize: 12, color: '#fff' }}>From: {fromDate}</Text>
              <Pressable onPress={() => setFromDate('')} hitSlop={10}><X size={12} color="#fff" /></Pressable>
            </View>
          )}
          {toDate !== '' && (
            <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: colors.accent, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12, gap: 4 }}>
              <Text style={{ fontSize: 12, color: '#fff' }}>To: {toDate}</Text>
              <Pressable onPress={() => setToDate('')} hitSlop={10}><X size={12} color="#fff" /></Pressable>
            </View>
          )}
          <Pressable onPress={clearAllFilters} style={{ justifyContent: 'center', paddingHorizontal: 4 }}>
            <Text style={{ fontSize: 12, color: colors.accent, fontWeight: '600' }}>Clear all</Text>
          </Pressable>
        </ScrollView>
      )}

      {/* Empty / Error */}
      {loadError ? (
        <EmptyState icon={WifiOff} title="Couldn't load audit logs" message="Pull down to try again." />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={FileText}
          title="No activity found"
          message={search || filter !== 'all' ? 'No events match your search or filters.' : 'No audit events have been recorded yet.'}
        />
      ) : (
        /* Grouped timeline */
        grouped.map(({ dateLabel, items }) => (
          <View key={dateLabel} style={{ gap: spacing.s3 }}>
            {/* Date header */}
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
              <Calendar size={13} color={colors.ink3} />
              <Text variant="label" color="secondary">{dateLabel}</Text>
            </View>

            {/* Events */}
            {items.map((log, idx) => {
              const tone = statusTone(log.status ?? '');
              const isLast = idx === items.length - 1;
              const dotColor =
                tone === 'success' ? colors.success :
                tone === 'error'   ? colors.error   :
                tone === 'warning' ? (colors.warnInk ?? '#B06000') :
                tone === 'info'    ? colors.accent  : colors.ink3;
              
              const KindIcon = KIND_META[log.kind].Icon;

              return (
                <View key={log.key} style={{ flexDirection: 'row', gap: spacing.s3 }}>
                  {/* Rail */}
                  <View style={{ alignItems: 'center', width: 20 }}>
                    <View style={{
                      width: 10, height: 10, borderRadius: 5,
                      backgroundColor: dotColor,
                      borderWidth: 2, borderColor: colors.surface,
                      marginTop: 4,
                    }} />
                    {!isLast ? (
                      <View style={{
                        flex: 1, width: 2,
                        backgroundColor: colors.border,
                        marginTop: 4,
                        minHeight: 20,
                      }} />
                    ) : null}
                  </View>

                  {/* Content */}
                  <View style={{
                    flex: 1,
                    backgroundColor: colors.surface,
                    borderRadius: radius.md,
                    borderWidth: 1, borderColor: colors.border,
                    padding: spacing.s3,
                    gap: spacing.s2,
                    marginBottom: isLast ? 0 : spacing.s1,
                  }}>
                    {/* Top: action label + time */}
                    <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                      <View style={{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: spacing.s2, flexWrap: 'wrap' }}>
                        <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                          {log.title}
                        </Text>
                        {log.status && <Badge tone={tone} label={log.status.replace(/_/g, ' ')} />}
                      </View>
                      <Text variant="caption" color="tertiary" style={{ marginLeft: spacing.s2 }}>
                        {fmtTime(log.ts)}
                      </Text>
                    </View>

                    {/* Meta tags (kind, amount, subtitle) */}
                    <View style={{ flexDirection: 'row', gap: spacing.s2, alignItems: 'center', flexWrap: 'wrap' }}>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                        <KindIcon size={11} color={colors.ink3} />
                        <Text variant="caption" color="secondary">{KIND_META[log.kind].label.replace(/s$/, '')}</Text>
                      </View>
                      {log.amount != null ? (
                        <Text variant="caption" color="secondary">• ₹{log.amount.toLocaleString('en-IN')}</Text>
                      ) : null}
                      {log.subtitle ? (
                        <Text variant="caption" color="secondary">• {log.subtitle}</Text>
                      ) : null}
                    </View>

                    {/* Reason */}
                    {log.reason ? (
                      <View style={{
                        backgroundColor: colors.warnBg ?? colors.subtle,
                        borderRadius: radius.sm,
                        padding: spacing.s2,
                        borderLeftWidth: 3,
                        borderLeftColor: colors.warnInk ?? '#B06000',
                      }}>
                        <Text variant="caption" color="secondary">Reason: {log.reason}</Text>
                      </View>
                    ) : null}

                    {/* Footer: actor + allocation */}
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, flexWrap: 'wrap' }}>
                      {log.actor ? (
                        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                          <User size={11} color={colors.ink3} />
                          <Text variant="caption" color="tertiary">
                            {log.actor}
                          </Text>
                        </View>
                      ) : null}
                      {log.allocationId ? (
                        <Text variant="caption" color="tertiary" style={{ fontFamily: 'monospace', fontSize: 10 }}>
                          #{log.allocationId.slice(0, 8)}
                        </Text>
                      ) : null}
                    </View>
                  </View>
                </View>
              );
            })}
          </View>
        ))
      )}

      {/* Filter Modal */}
      <Modal visible={modalOpen} transparent animationType="slide">
        <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' }}>
          <View style={{ backgroundColor: colors.surface, borderTopLeftRadius: 20, borderTopRightRadius: 20, padding: spacing.s5 }}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.s4 }}>
              <Text variant="title">Filter events</Text>
              <Pressable onPress={() => setModalOpen(false)} hitSlop={10}><X size={20} color={colors.ink2} /></Pressable>
            </View>

            <Text variant="label" style={{ marginBottom: spacing.s2 }}>Event type</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.s2, marginBottom: spacing.s5 }}>
              {FILTERS.map(f => (
                <Pressable
                  key={f.id}
                  onPress={() => setTempFilter(f.id)}
                  style={{
                    paddingHorizontal: 12, paddingVertical: 8, borderRadius: radius.md,
                    backgroundColor: tempFilter === f.id ? colors.ink1 : colors.subtle,
                    borderWidth: 1, borderColor: tempFilter === f.id ? colors.ink1 : colors.border
                  }}
                >
                  <Text style={{ color: tempFilter === f.id ? colors.surface : colors.ink1, fontWeight: '500' }}>
                    {f.label}
                  </Text>
                </Pressable>
              ))}
            </View>

            <View style={{ flexDirection: 'row', gap: spacing.s4, marginBottom: spacing.s6 }}>
              <View style={{ flex: 1 }}>
                <Text variant="label" style={{ marginBottom: spacing.s2 }}>From Date</Text>
                <TextInput
                  value={tempFromDate}
                  onChangeText={setTempFromDate}
                  placeholder="YYYY-MM-DD"
                  placeholderTextColor={colors.ink3}
                  style={{ borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: spacing.s3, color: colors.ink1 }}
                />
              </View>
              <View style={{ flex: 1 }}>
                <Text variant="label" style={{ marginBottom: spacing.s2 }}>To Date</Text>
                <TextInput
                  value={tempToDate}
                  onChangeText={setTempToDate}
                  placeholder="YYYY-MM-DD"
                  placeholderTextColor={colors.ink3}
                  style={{ borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: spacing.s3, color: colors.ink1 }}
                />
              </View>
            </View>

            <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
              <Pressable 
                onPress={clearAllFilters}
                style={{ flex: 1, padding: spacing.s3, borderRadius: radius.md, backgroundColor: colors.subtle, alignItems: 'center' }}
              >
                <Text style={{ color: colors.ink1, fontWeight: '600' }}>Clear All</Text>
              </Pressable>
              <Pressable 
                onPress={applyFilters}
                style={{ flex: 1, padding: spacing.s3, borderRadius: radius.md, backgroundColor: colors.accent, alignItems: 'center' }}
              >
                <Text style={{ color: '#fff', fontWeight: '600' }}>Apply Filters</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>
    </ScrollView>
  );
}

// ─── Admin Actions Feed ───────────────────────────────────────────────────────

interface AdminFeedProps {
  actions: UserActionAuditResponse[];
  loading: boolean;
  refreshing: boolean;
  loadError: boolean;
  onRefresh: () => void;
}

function AdminFeed({ actions, loading, refreshing, loadError, onRefresh }: AdminFeedProps) {
  const { colors, spacing, radius } = useTheme();

  if (loading) return <LoadingView label="Loading admin actions..." />;

  return (
    <FlatList
      data={actions}
      keyExtractor={(item) => item.id}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
      contentContainerStyle={{ gap: spacing.s3, paddingBottom: 40 }}
      ListEmptyComponent={
        loadError ? (
          <EmptyState icon={WifiOff} title="Couldn't load admin actions" message="Pull down to try again." />
        ) : (
          <EmptyState icon={ShieldCheck} title="No admin actions recorded" message="Admin and system actions will appear here." />
        )
      }
      renderItem={({ item }) => (
        <View style={{
          backgroundColor: colors.surface,
          borderRadius: radius.md,
          borderWidth: 1, borderColor: colors.border,
          padding: spacing.s4,
          gap: spacing.s2,
        }}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
              {item.action.replace(/_/g, ' ')}
            </Text>
            <Badge tone={statusTone(item.action)} label={item.action.replace(/_/g, ' ')} />
          </View>
          {item.details ? (
            <Text variant="body" color="secondary">{item.details}</Text>
          ) : null}
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
              <User size={11} color={colors.ink3} />
              <Text variant="caption" color="tertiary">{item.userEmail}</Text>
            </View>
            <Text variant="caption" color="tertiary" style={{ fontSize: 11 }}>
              {fmtDateTime(item.createdAt)}
            </Text>
          </View>
        </View>
      )}
    />
  );
}

// ─── Main Screen ──────────────────────────────────────────────────────────────

export default function AuditLogsScreen() {
  const { colors, spacing, radius } = useTheme();
  const { role } = useAuth();
  const isPlatformAdmin = role === 'PLATFORM_ADMIN';

  const [tab, setTab] = useState<Tab>('activity');

  // Activity tab state
  const [logs, setLogs] = useState<OrgEvent[]>([]);
  const [activityLoading, setActivityLoading] = useState(true);
  const [activityRefreshing, setActivityRefreshing] = useState(false);
  const [activityError, setActivityError] = useState(false);
  const [search, setSearch] = useState('');

  // Admin tab state
  const [adminActions, setAdminActions] = useState<UserActionAuditResponse[]>([]);
  const [adminLoading, setAdminLoading] = useState(false);
  const [adminRefreshing, setAdminRefreshing] = useState(false);
  const [adminError, setAdminError] = useState(false);
  const [adminLoaded, setAdminLoaded] = useState(false);

  // ── Load activity ─────────────────────────────────────────────────────────

  const loadActivity = useCallback(async () => {
    try {
      const [auditRes, visitsRes, colsRes, ptpsRes] = await Promise.allSettled([
        auditApi.getByOrganization(0, 50),
        visitLogApi.listMyVisits({ page: 0, size: 50 }),
        collectionsApi.list({ page: 0, size: 50 }),
        ptpsApi.list({ page: 0, size: 50 }),
      ]);
      
      const all: OrgEvent[] = [];
      if (auditRes.status === 'fulfilled')  (auditRes.value.content ?? []).forEach(l => all.push(fromAudit(l)));
      if (visitsRes.status === 'fulfilled') (visitsRes.value.content ?? []).forEach(v => all.push(fromVisit(v)));
      if (colsRes.status === 'fulfilled')   (colsRes.value.content ?? []).forEach(c => all.push(fromCollection(c)));
      if (ptpsRes.status === 'fulfilled')   (ptpsRes.value.content ?? []).forEach(p => all.push(fromPtp(p)));
      
      all.sort((a, b) => new Date(b.ts).getTime() - new Date(a.ts).getTime());
      
      setLogs(all);
      setActivityError(false);
    } catch {
      setActivityError(true);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      setActivityLoading(true);
      loadActivity().finally(() => setActivityLoading(false));
    }, [loadActivity]),
  );

  const onRefreshActivity = async () => {
    setActivityRefreshing(true);
    await loadActivity();
    setActivityRefreshing(false);
  };

  // ── Load admin actions ────────────────────────────────────────────────────

  const loadAdmin = useCallback(async () => {
    try {
      const res = await auditApi.getAllActionLogs(0, 100);
      setAdminActions(res.content ?? []);
      setAdminError(false);
    } catch {
      setAdminError(true);
    }
  }, []);

  const onTabChange = (t: Tab) => {
    setTab(t);
    if (t === 'admin' && !adminLoaded) {
      setAdminLoading(true);
      loadAdmin().finally(() => { setAdminLoading(false); setAdminLoaded(true); });
    }
  };

  const onRefreshAdmin = async () => {
    setAdminRefreshing(true);
    await loadAdmin();
    setAdminRefreshing(false);
  };

  // ─────────────────────────────────────────────────────────────────────────

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ flex: 1, gap: spacing.s4 }}>

        {/* Header */}
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Audit logs</Text>
        </View>
        </View>

        {/* Tabs — only show for PLATFORM_ADMIN */}
        {isPlatformAdmin ? (
          <View style={{
            flexDirection: 'row',
            backgroundColor: colors.subtle,
            borderRadius: radius.md,
            padding: 4,
            borderWidth: 1, borderColor: colors.border,
          }}>
            {([
              { id: 'activity' as Tab, label: 'Activity', Icon: FileText },
              { id: 'admin' as Tab, label: 'Admin Actions', Icon: ShieldCheck },
            ]).map(({ id, label, Icon }) => (
              <Pressable
                key={id}
                onPress={() => onTabChange(id)}
                style={{
                  flex: 1, flexDirection: 'row', alignItems: 'center',
                  justifyContent: 'center', gap: 5,
                  paddingVertical: spacing.s2,
                  borderRadius: radius.sm,
                  backgroundColor: tab === id ? colors.surface : 'transparent',
                  shadowColor: tab === id ? '#000' : 'transparent',
                  shadowOpacity: tab === id ? 0.06 : 0,
                  shadowRadius: 4,
                  elevation: tab === id ? 2 : 0,
                }}
              >
                <Icon size={13} color={tab === id ? colors.ink1 : colors.ink3} />
                <Text style={{
                  fontSize: 13,
                  fontFamily: tab === id ? 'Inter_600SemiBold' : 'Inter_400Regular',
                  color: tab === id ? colors.ink1 : colors.ink3,
                }}>
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
        ) : null}

        {/* Content */}
        {tab === 'activity' ? (
          <ActivityFeed
            logs={logs}
            loading={activityLoading}
            refreshing={activityRefreshing}
            loadError={activityError}
            onRefresh={onRefreshActivity}
            search={search}
            setSearch={setSearch}
          />
        ) : (
          <AdminFeed
            actions={adminActions}
            loading={adminLoading}
            refreshing={adminRefreshing}
            loadError={adminError}
            onRefresh={onRefreshAdmin}
          />
        )}
      </View>
    </Screen>
  );
}
