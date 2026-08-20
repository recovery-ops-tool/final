import { useCallback, useMemo, useState } from 'react';
import { FlatList, Pressable, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect, useRouter } from 'expo-router';
import {
  Search, WifiOff, X, Phone, PhoneOff, Clock, Mic,
} from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import {
  Text, EmptyState, LoadingView, Card, Badge, Divider,
} from '@/components/ui';
import { callLogApi } from '@/api/callLogApi';
import { formatDateTime } from '@/utils/date';
import type { CallLogResponse, CallOutcome } from '@/types/domain';

const OUTCOME_LABELS: Record<CallOutcome, string> = {
  ANSWERED: 'Answered',
  NO_ANSWER: 'No answer',
  BUSY: 'Busy',
  WRONG_NUMBER: 'Wrong number',
  CALLBACK_REQUESTED: 'Callback requested',
  REFUSED: 'Refused',
  SWITCHED_OFF: 'Switched off',
};

function outcomeTone(outcome?: CallOutcome) {
  switch (outcome) {
    case 'ANSWERED': return 'success';
    case 'CALLBACK_REQUESTED': return 'warning';
    case 'BUSY': return 'warning';
    case 'WRONG_NUMBER': return 'error';
    case 'REFUSED': return 'error';
    default: return 'neutral';
  }
}

function formatDuration(seconds?: number): string | null {
  if (!seconds) return null;
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

export default function CallsScreen() {
  const router = useRouter();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [callLogs, setCallLogs] = useState<CallLogResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    try {
      const response = await callLogApi.list({ size: 50 });
      setCallLogs(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      console.log('Failed to load call logs', e);
      setLoadError(true);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load]),
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return callLogs;
    return callLogs.filter((c) =>
      c.borrowerName?.toLowerCase().includes(q)
      || c.loanNumber?.toLowerCase().includes(q)
      || c.phoneMasked?.toLowerCase().includes(q));
  }, [callLogs, search]);

  if (loading) return <LoadingView label="Loading calls…" />;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.canvas }} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Call history</Text>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2,
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search calls by borrower or loan"
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>
      </View>

      <FlatList
        data={filtered}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s2 }}
        renderItem={({ item }) => (
          <Pressable onPress={() => router.push({ pathname: '/(org)/case/[id]', params: { id: item.allocationId } })}>
            <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1, flex: 1, marginRight: spacing.s2 }}>
                  {item.borrowerName || item.phoneMasked || 'Unknown borrower'}
                </Text>
                {item.outcome ? <Badge tone={outcomeTone(item.outcome)} label={OUTCOME_LABELS[item.outcome]} /> : null}
              </View>

              {item.loanNumber ? (
                <Text variant="caption" color="secondary">Loan: {item.loanNumber}</Text>
              ) : null}

              <Divider style={{ marginVertical: 4 }} />

              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s4 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
                  <Clock size={14} color={colors.ink3} />
                  <Text variant="caption" color="secondary">{formatDateTime(item.initiatedAt)}</Text>
                </View>
                {formatDuration(item.durationSeconds) ? (
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
                    <Phone size={14} color={colors.ink3} />
                    <Text variant="caption" color="secondary">{formatDuration(item.durationSeconds)}</Text>
                  </View>
                ) : null}
                {item.recordingStatus === 'UPLOADED' ? (
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
                    <Mic size={14} color={colors.ink3} />
                    <Text variant="caption" color="secondary">Recorded</Text>
                  </View>
                ) : null}
              </View>

              {item.notes ? (
                <Text variant="caption" color="secondary" style={{ marginTop: 2, fontStyle: 'italic' }}>
                  &quot;{item.notes}&quot;
                </Text>
              ) : null}
            </Card>
          </Pressable>
        )}
        refreshing={refreshing}
        onRefresh={onRefresh}
        ListEmptyComponent={
          loadError
            ? <EmptyState icon={WifiOff} title="Couldn't load calls" message="Pull down to try again." />
            : (
              <EmptyState
                icon={PhoneOff}
                title="No calls logged yet"
                message="Calls you place from a case will show up here."
              />
            )
        }
      />
    </SafeAreaView>
  );
}
