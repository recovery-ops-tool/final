import { useCallback, useState } from 'react';
import { View, Modal, Image, Pressable } from 'react-native';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import {
  MapPin, Handshake, Link2, Receipt, AlertTriangle, CloudOff, X, ImageIcon, MessageSquare,
} from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Card, Badge, Button, Divider, LoadingView, EmptyState } from '@/components/ui';
import { allocationsApi } from '@/api/allocationsApi';
import { casesApi } from '@/api/casesApi';
import { visitLogApi } from '@/api/visitLogApi';
import { ptpsApi } from '@/api/ptpsApi';
import { collectionsApi } from '@/api/collectionsApi';
import { allocationStatusTone, dispositionTone, collectionStatusTone, DISP_LABELS } from '@/utils/statusStyles';
import { resolveAmount, formatCurrency } from '@/utils/allocationHeuristics';
import { mergeTimeline, type TimelineEntry } from '@/utils/mergeTimeline';
import { styleForEvent } from '@/utils/timelineStyles';
import { formatDateTime, formatDate } from '@/utils/date';
import type { AllocationResponse, CollectionResponse, VisitLogResponse } from '@/types/domain';

function humanizeKey(key: string): string {
  return key
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function formatDynamicValue(value: unknown): string {
  if (value == null || value === '') return '—';
  return String(value);
}

export default function CaseDetailScreen() {
  const { id, savedOffline } = useLocalSearchParams<{ id: string; savedOffline?: string }>();
  const { colors, spacing } = useTheme();

  const [loading, setLoading] = useState(true);
  const [allocation, setAllocation] = useState<AllocationResponse | null>(null);
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [recentVisits, setRecentVisits] = useState<VisitLogResponse[]>([]);
  const [collections, setCollections] = useState<CollectionResponse[]>([]);
  const [viewerUrl, setViewerUrl] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    const [allocResult, timelineResult, visitsResult, ptpsResult, collectionsResult] = await Promise.allSettled([
      allocationsApi.getById(id),
      casesApi.getTimeline(id),
      visitLogApi.getByAllocation(id),
      ptpsApi.getByAllocation(id),
      collectionsApi.getByAllocation(id),
    ]);

    if (allocResult.status === 'fulfilled') setAllocation(allocResult.value);
    const events = timelineResult.status === 'fulfilled' ? timelineResult.value.events : [];
    const visits = visitsResult.status === 'fulfilled' ? visitsResult.value : [];
    const ptps = ptpsResult.status === 'fulfilled' ? ptpsResult.value : [];
    const cols = collectionsResult.status === 'fulfilled' ? collectionsResult.value : [];

    setRecentVisits([...visits].sort((a, b) => new Date(b.visitDate).getTime() - new Date(a.visitDate).getTime()).slice(0, 3));
    setCollections([...cols].sort((a, b) => new Date(b.collectionDate).getTime() - new Date(a.collectionDate).getTime()));
    setTimeline(mergeTimeline(events, visits, ptps));
  }, [id]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load]),
  );

  if (loading) return <LoadingView label="Loading case…" />;
  if (!allocation) {
    return (
      <Screen>
        <EmptyState icon={AlertTriangle} title="Case not found" message="This case may have been reassigned or removed." />
      </Screen>
    );
  }

  const amount = resolveAmount(allocation);

  return (
    <Screen>
      <View style={{ gap: spacing.s5 }}>
        {savedOffline === '1' ? (
          <Card style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            <CloudOff size={18} color={colors.warnBorder} />
            <Text variant="caption" color="secondary" style={{ flex: 1 }}>
              Saved on your device — it'll sync automatically once you're back online.
            </Text>
          </Card>
        ) : null}

        <Card style={{ gap: spacing.s3 }}>
          <View>
            <Text variant="title" style={{ fontSize: 20 }}>{allocation.borrowerName || 'Unknown borrower'}</Text>
            <Text variant="caption" color="secondary">{allocation.loanNumber}</Text>
          </View>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.s2 }}>
            <Badge tone={allocationStatusTone(allocation.status)} label={allocation.status} />
            {allocation.latestDisposition ? (
              <Badge tone={dispositionTone(allocation.latestDisposition)} label={DISP_LABELS[allocation.latestDisposition]} />
            ) : null}
            {allocation.npaFlagged ? <Badge tone="error" label="NPA" /> : null}
          </View>
          <Divider />
          <View style={{ flexDirection: 'row' }}>
            <View style={{ flex: 1 }}>
              <Text variant="caption" color="secondary">Outstanding</Text>
              <Text variant="headline">{formatCurrency(amount)}</Text>
            </View>
            <View style={{ flex: 1 }}>
              <Text variant="caption" color="secondary">Total due</Text>
              <Text variant="headline">{formatCurrency(allocation.totalDue ?? null)}</Text>
            </View>
            {allocation.emi != null ? (
              <View style={{ flex: 1 }}>
                <Text variant="caption" color="secondary">EMI</Text>
                <Text variant="headline">{formatCurrency(allocation.emi)}</Text>
              </View>
            ) : null}
          </View>
        </Card>

        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.s3 }}>
          <Button
            label="Visit interview"
            fullWidth={false}
            onPress={() => router.push({ pathname: '/case/[id]/lucien-visit', params: { id } })}
            icon={<MessageSquare size={16} color="#fff" />}
          />
          <Button
            label="Log a visit"
            fullWidth={false}
            onPress={() => router.push({ pathname: '/case/[id]/visit', params: { id } })}
            icon={<MapPin size={16} color="#fff" />}
          />
          <Button
            label="Create PTP"
            fullWidth={false}
            variant="secondary"
            onPress={() => router.push({ pathname: '/case/[id]/ptp', params: { id } })}
            icon={<Handshake size={16} color={colors.ink1} />}
          />
          <Button
            label="Payment link"
            fullWidth={false}
            variant="secondary"
            onPress={() => router.push({ pathname: '/case/[id]/payment-link', params: { id } })}
            icon={<Link2 size={16} color={colors.ink1} />}
          />
        </View>

        {recentVisits.some((v) => v.disp === 'PAID' && !v.collectionId) ? (
          <Card style={{ gap: spacing.s3 }}>
            <Text variant="headline">Recent visits</Text>
            {recentVisits.map((v) => (
              <View key={v.id} style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
                <View style={{ flex: 1 }}>
                  <Text variant="bodyMedium">{formatDate(v.visitDate)} — {v.disp ? DISP_LABELS[v.disp] : 'Logged'}</Text>
                  {v.amountCollected ? <Text variant="caption" color="secondary">{formatCurrency(v.amountCollected)} collected</Text> : null}
                </View>
                {v.disp === 'PAID' && !v.collectionId ? (
                  <Button
                    label="Record collection"
                    fullWidth={false}
                    size="md"
                    icon={<Receipt size={14} color="#fff" />}
                    onPress={() => router.push({ pathname: '/case/[id]/collection', params: { id, visitId: v.id, amount: String(v.amountCollected ?? '') } })}
                  />
                ) : null}
              </View>
            ))}
          </Card>
        ) : null}

        {collections.length > 0 ? (
          <Card style={{ gap: spacing.s3 }}>
            <Text variant="headline">Collections</Text>
            {collections.map((c) => (
              <View key={c.id} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
                <View style={{ flex: 1 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                    <Text variant="bodyMedium">{formatCurrency(c.amount)}</Text>
                    <Badge tone={collectionStatusTone(c.status)} label={c.status.replace('_', ' ')} />
                  </View>
                  <Text variant="caption" color="secondary">{formatDate(c.collectionDate)} · {c.paymentMode}</Text>
                </View>
                {c.documents?.[0]?.fileUrl ? (
                  <Pressable onPress={() => setViewerUrl(c.documents![0].fileUrl)}>
                    <Image source={{ uri: c.documents[0].fileUrl }} style={{ width: 48, height: 48, borderRadius: 8 }} />
                  </Pressable>
                ) : (
                  <View style={{
                    width: 48, height: 48, borderRadius: 8, backgroundColor: colors.subtle,
                    alignItems: 'center', justifyContent: 'center',
                  }}
                  >
                    <ImageIcon size={18} color={colors.ink3} />
                  </View>
                )}
              </View>
            ))}
          </Card>
        ) : null}

        {Object.keys(allocation.dynamicData || {}).length > 0 ? (
          <Card style={{ gap: spacing.s2 }}>
            <Text variant="headline">Borrower details</Text>
            {Object.entries(allocation.dynamicData).map(([key, value]) => (
              <View key={key} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: spacing.s1 }}>
                <Text variant="caption" color="secondary" style={{ flex: 1 }}>{humanizeKey(key)}</Text>
                <Text variant="body" style={{ flex: 1, textAlign: 'right' }}>{formatDynamicValue(value)}</Text>
              </View>
            ))}
          </Card>
        ) : null}

        <View style={{ gap: spacing.s3 }}>
          <Text variant="headline">Activity</Text>
          {timeline.length === 0 ? (
            <EmptyState title="No activity yet" message="Visits, PTPs, and collections for this case will appear here." />
          ) : (
            <Card style={{ gap: 0 }}>
              {timeline.map((entry, index) => {
                const style = styleForEvent(entry.eventType as any);
                const Icon = style.icon;
                return (
                  <View key={`${entry.sourceId}-${index}`}>
                    <View style={{ flexDirection: 'row', gap: spacing.s3, paddingVertical: spacing.s3 }}>
                      <View style={{
                        width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
                        backgroundColor: colors.subtle,
                      }}
                      >
                        <Icon size={16} color={colors.ink2} />
                      </View>
                      <View style={{ flex: 1 }}>
                        <Text variant="bodyMedium">{entry.summary}</Text>
                        {entry.actorName ? <Text variant="caption" color="secondary">{entry.actorName}</Text> : null}
                        {entry.narrative ? <Text variant="caption" color="secondary">{entry.narrative}</Text> : null}
                        <Text variant="caption" color="tertiary">{formatDateTime(entry.timestamp)}</Text>
                      </View>
                    </View>
                    {index < timeline.length - 1 ? <Divider /> : null}
                  </View>
                );
              })}
            </Card>
          )}
        </View>
      </View>

      <Modal visible={!!viewerUrl} transparent animationType="fade" onRequestClose={() => setViewerUrl(null)}>
        <Pressable
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.92)', alignItems: 'center', justifyContent: 'center' }}
          onPress={() => setViewerUrl(null)}
        >
          {viewerUrl ? (
            <Image source={{ uri: viewerUrl }} style={{ width: '100%', height: '80%' }} resizeMode="contain" />
          ) : null}
          <Pressable
            onPress={() => setViewerUrl(null)}
            style={{
              position: 'absolute', top: 60, right: 20, width: 40, height: 40, borderRadius: 20,
              backgroundColor: 'rgba(255,255,255,0.15)', alignItems: 'center', justifyContent: 'center',
            }}
          >
            <X size={22} color="#fff" />
          </Pressable>
        </Pressable>
      </Modal>
    </Screen>
  );
}
