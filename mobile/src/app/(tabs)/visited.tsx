import { useCallback, useMemo, useState } from 'react';
import { FlatList, Pressable, TextInput, View } from 'react-native';
import { useFocusEffect, useRouter } from 'expo-router';
import { Search, WifiOff, X, ClipboardCheck, Calendar, Clock } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Text, EmptyState, LoadingView, Card, Badge, Divider } from '@/components/ui';
import { visitLogApi } from '@/api/visitLogApi';
import type { VisitLogResponse } from '@/types/domain';
import { SafeAreaView } from 'react-native-safe-area-context';

export default function VisitedCasesScreen() {
  const { user } = useAuth();
  const router = useRouter();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [visitLogs, setVisitLogs] = useState<VisitLogResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await visitLogApi.listMyVisits();
      setVisitLogs(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      console.log('Failed to load visited logs via visitLogApi', e);
      setLoadError(true);
    }
  }, [user]);

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
    const base = q
      ? visitLogs.filter((v) => 
          v.borrowerName?.toLowerCase().includes(q) || 
          v.loanNumber?.toLowerCase().includes(q) ||
          v.disp?.toLowerCase().includes(q)
        )
      : visitLogs;

    return [...base];
  }, [visitLogs, search]);

  const getDispTone = (disp?: string) => {
    switch (disp) {
      case 'PAID':
        return 'success';
      case 'PTP':
        return 'warning';
      case 'CONTACTED':
        return 'info';
      case 'NO_CONTACT':
        return 'error';
      default:
        return 'neutral';
    }
  };

  const getApprovalTone = (status: string) => {
    switch (status) {
      case 'APPROVED':
        return 'success';
      case 'REJECTED':
        return 'error';
      default:
        return 'warning';
    }
  };

  if (loading) return <LoadingView label="Loading visited logs…" />;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.canvas }} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s4, gap: spacing.s4 }}>
        <View>
          <Text variant="title">Visited Logs</Text>
          <Text variant="caption" color="secondary">{filtered.length} total visit records logged</Text>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border,
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search visits by borrower, loan or status"
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
        contentContainerStyle={{ padding: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
        renderItem={({ item }) => (
          <Pressable onPress={() => router.push({ pathname: '/visit-detail/[id]', params: { id: item.id } })}>
            <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
              
              {/* Header row: Borrower Name & Disposition Badge */}
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1, flex: 1, marginRight: spacing.s2 }}>
                  {item.borrowerName || 'Unknown Borrower'}
                </Text>
                {item.disp ? (
                  <Badge tone={getDispTone(item.disp)} label={item.disp} />
                ) : null}
              </View>

              {/* Sub-header row: Loan Number & Approval Status */}
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="caption" color="secondary">
                  Loan: {item.loanNumber || '—'}
                </Text>
                <Badge tone={getApprovalTone(item.approvalStatus)} label={item.approvalStatus} />
              </View>

              <Divider style={{ marginVertical: 4 }} />

              {/* Footer row: Date & Time */}
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s4 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
                  <Calendar size={14} color={colors.ink3} />
                  <Text variant="caption" color="secondary">{item.visitDate}</Text>
                </View>
                {item.visitTime ? (
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
                    <Clock size={14} color={colors.ink3} />
                    <Text variant="caption" color="secondary">{item.visitTime}</Text>
                  </View>
                ) : null}
              </View>

              {item.visitOutcome ? (
                <Text variant="caption" color="secondary" style={{ marginTop: 2, fontStyle: 'italic' }}>
                  Outcome: "{item.visitOutcome}"
                </Text>
              ) : null}

            </Card>
          </Pressable>
        )}
        refreshing={refreshing}
        onRefresh={onRefresh}
        ListEmptyComponent={
          loadError
            ? <EmptyState icon={WifiOff} title="Couldn't load visited logs" message="Pull down to try again." />
            : (
              <EmptyState
                icon={ClipboardCheck}
                title="No visits logged yet"
                message="Your completed field visit reports will be listed here."
              />
            )
        }
      />
    </SafeAreaView>
  );
}
